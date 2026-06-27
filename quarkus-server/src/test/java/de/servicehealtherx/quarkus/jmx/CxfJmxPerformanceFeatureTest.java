package de.servicehealtherx.quarkus.jmx;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.management.InstrumentationManager;
import org.apache.cxf.management.counters.CounterRepository;
import org.apache.cxf.management.jmx.InstrumentationManagerImpl;
import org.junit.jupiter.api.Test;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link CxfJmxPerformanceFeature} actually wires CXF's JMX management and response-time
 * performance counters onto a CXF {@link Bus}, exercised against the real {@code cxf-rt-management}
 * classes (no Quarkus boot needed).
 */
class CxfJmxPerformanceFeatureTest {

    @Test
    void enablesInstrumentationManagerCountersAndRegistersBusMBean() throws Exception {
        Bus bus = BusFactory.newInstance().createBus();
        try {
            new CxfJmxPerformanceFeature().initialize(bus);

            // 1) InstrumentationManager is present, enabled and bound to the platform MBean server.
            InstrumentationManager im = bus.getExtension(InstrumentationManager.class);
            InstrumentationManagerImpl impl = assertInstanceOf(InstrumentationManagerImpl.class, im,
                    "InstrumentationManager extension must be present");
            assertTrue(impl.isEnabled(), "InstrumentationManager must be enabled");
            assertSame(ManagementFactory.getPlatformMBeanServer(), impl.getMBeanServer(),
                    "must use the JVM platform MBean server so Hawtio can read it");

            // 2) A CounterRepository is registered and the response-time interceptors are on the bus.
            assertNotNull(bus.getExtension(CounterRepository.class),
                    "CounterRepository (performance counters) must be registered on the bus");
            assertTrue(bus.getInInterceptors().stream()
                            .anyMatch(i -> i.getClass().getName().contains("ResponseTime")),
                    "bus must carry the response-time in-interceptor");
            assertTrue(bus.getOutInterceptors().stream()
                            .anyMatch(i -> i.getClass().getName().contains("ResponseTime")),
                    "bus must carry the response-time out-interceptor");

            // 3) At least the bus MBean is registered under the org.apache.cxf JMX domain.
            assertFalse(ManagementFactory.getPlatformMBeanServer()
                            .queryNames(new ObjectName("org.apache.cxf:*"), null).isEmpty(),
                    "expected CXF MBeans registered under the org.apache.cxf domain");
        } finally {
            bus.shutdown(true);
        }
    }

    @Test
    void isIdempotentAcrossMultipleEndpoints() {
        Bus bus = BusFactory.newInstance().createBus();
        try {
            CxfJmxPerformanceFeature feature = new CxfJmxPerformanceFeature();
            // Simulate the feature being initialized once per endpoint on the same bus.
            feature.initialize(bus);
            CounterRepository first = bus.getExtension(CounterRepository.class);
            feature.initialize(bus);
            feature.initialize(bus);

            assertSame(first, bus.getExtension(CounterRepository.class),
                    "repeated initialization must not replace the CounterRepository");
            long responseTimeInInterceptors = bus.getInInterceptors().stream()
                    .filter(i -> i.getClass().getName().contains("ResponseTime"))
                    .count();
            // CounterRepository#setBus adds one ResponseTime in-interceptor and one invoker; the
            // guard must prevent these from being added again on subsequent endpoints.
            assertTrue(responseTimeInInterceptors <= 2,
                    "response-time interceptors must not be added repeatedly, found: "
                            + responseTimeInInterceptors);
        } finally {
            bus.shutdown(true);
        }
    }
}
