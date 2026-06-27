package de.servicehealtherx.quarkus.jmx;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.cxf.Bus;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.feature.Feature;
import org.apache.cxf.interceptor.InterceptorProvider;
import org.apache.cxf.management.InstrumentationManager;
import org.apache.cxf.management.counters.CounterRepository;
import org.apache.cxf.management.jmx.InstrumentationManagerImpl;

/**
 * CXF {@link Feature} that turns on JMX management and <em>response-time performance counters</em>
 * for the SOAP endpoints it is attached to.
 *
 * <p>It wires two CXF management building blocks onto the CXF {@link Bus}, once per bus:
 * <ul>
 *   <li>The {@link InstrumentationManager} (from {@code cxf-rt-management}) is enabled against the
 *       JVM <em>platform</em> MBean server, so the bus, every published endpoint and the counters
 *       below appear as MBeans — readable by the embedded Hawtio console (and any JMX client).</li>
 *   <li>A {@link CounterRepository} registers CXF's response-time interceptors on the bus, so every
 *       request is measured and exposed as per-service and per-operation
 *       {@code Performance.Counter.Server} MBeans
 *       ({@code NumInvocations}, {@code AvgResponseTime}, {@code MinResponseTime},
 *       {@code MaxResponseTime}, {@code TotalHandlingTime}, ...).</li>
 * </ul>
 *
 * <p>Why a {@link Feature} and not a startup bean: a feature's {@code initialize} runs while the
 * endpoint's CXF {@code Server} is being created, i.e. <em>before</em> that server is started — and
 * starting a server is exactly when CXF registers its per-endpoint MBean (only if the
 * InstrumentationManager is already enabled). Attaching this feature to every endpoint therefore
 * guarantees JMX is enabled before the first endpoint starts, so all endpoint MBeans register.
 *
 * <p>The work is idempotent: it is guarded by a bus property and only ever configures a given bus
 * once, regardless of how many endpoints reference the feature.
 *
 * <p>Attach it per endpoint via configuration, e.g.
 * {@code quarkus.cxf.endpoint."/conn/EventService".features=de.servicehealtherx.quarkus.jmx.CxfJmxPerformanceFeature}.
 */
public class CxfJmxPerformanceFeature implements Feature {

    private static final Logger LOG = Logger.getLogger(CxfJmxPerformanceFeature.class.getName());

    /** Marks a bus whose JMX management + counters this feature has already configured. */
    private static final String CONFIGURED = "de.servicehealtherx.quarkus.jmx.configured";

    @Override
    public void initialize(Server server, Bus bus) {
        enableManagement(bus);
    }

    @Override
    public void initialize(Client client, Bus bus) {
        enableManagement(bus);
    }

    @Override
    public void initialize(InterceptorProvider interceptorProvider, Bus bus) {
        enableManagement(bus);
    }

    @Override
    public void initialize(Bus bus) {
        enableManagement(bus);
    }

    private static synchronized void enableManagement(Bus bus) {
        if (bus == null || Boolean.TRUE.equals(bus.getProperty(CONFIGURED))) {
            return;
        }
        try {
            enableInstrumentationManager(bus);
            enablePerformanceCounters(bus);
            bus.setProperty(CONFIGURED, Boolean.TRUE);
            LOG.info("CXF JMX management and response-time performance counters enabled on bus "
                    + bus.getId());
        } catch (RuntimeException e) {
            // Management is observability, not a functional requirement: never let it break startup.
            LOG.log(Level.WARNING, "Could not enable CXF JMX management/performance counters", e);
        }
    }

    /** Enable the JMX InstrumentationManager on the JVM platform MBean server (Hawtio-visible). */
    private static void enableInstrumentationManager(Bus bus) {
        InstrumentationManager im = bus.getExtension(InstrumentationManager.class);
        if (im == null) {
            // Seed the JMX bus properties so the lazily created extension reads them on construction.
            bus.setProperty("bus.jmx.enabled", Boolean.TRUE);
            bus.setProperty("bus.jmx.usePlatformMBeanServer", Boolean.TRUE);
            im = bus.getExtension(InstrumentationManager.class);
        }
        if (im instanceof InstrumentationManagerImpl impl) {
            impl.setUsePlatformMBeanServer(true);
            impl.setEnabled(true);
            // init() is what actually grabs the MBean server and registers the bus MBean; only call
            // it when that has not happened yet (avoids re-registering the bus lifecycle listener).
            if (impl.getMBeanServer() == null) {
                impl.init();
            }
        }
    }

    /** Register response-time counters on the bus (covers every endpoint on it). */
    private static void enablePerformanceCounters(Bus bus) {
        if (bus.getExtension(CounterRepository.class) == null) {
            CounterRepository counters = new CounterRepository();
            // setBus adds the response-time in/out interceptors to the bus and registers itself as
            // the bus' CounterRepository extension, which the interceptors look up to record timings.
            counters.setBus(bus);
        }
    }
}
