package de.servicehealtherx.crypto.p12;

import de.servicehealtherx.crypto.adapter.P12KeyStoreAdapter;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class P12CertScanner {

    private static final Logger LOG = Logger.getLogger(P12CertScanner.class);

    public List<P12KeyStoreAdapter> scan(String certsDirPath) {
        Path certsDir = Paths.get(certsDirPath);
        bootstrapFromClasspath(certsDirPath, certsDir);

        List<P12KeyStoreAdapter> adapters = new ArrayList<>();
        if (!Files.exists(certsDir)) {
            LOG.warnf("[P12Scanner] certs directory does not exist: %s", certsDir);
            return adapters;
        }

        try (Stream<Path> walk = Files.walk(certsDir)) {
            walk.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".p12") || name.endsWith(".pfx");
            }).forEach(p12Path -> adapters.add(loadAdapter(certsDir, p12Path)));
        } catch (IOException e) {
            LOG.errorf(e, "[P12Scanner] failed to walk certs directory: %s", certsDir);
        }

        return adapters;
    }

    private P12KeyStoreAdapter loadAdapter(Path certsDir, Path p12Path) {
        Path passwordFile = p12Path.getParent().resolve("password.txt");
        String alias = deriveAlias(certsDir, p12Path);

        if (!Files.exists(passwordFile)) {
            LOG.warnf("[P12Scanner] missing password.txt for %s — alias=%s marked ERROR", p12Path, alias);
            P12KeyStoreAdapter adapter = new P12KeyStoreAdapter(alias, p12Path.toAbsolutePath().toString(), "", "");
            adapter.descriptor().markError("missing password.txt sibling for " + p12Path.getFileName());
            return adapter;
        }

        String password;
        try {
            password = Files.readString(passwordFile).trim();
        } catch (IOException e) {
            LOG.warnf("[P12Scanner] cannot read password.txt for %s — marked ERROR", p12Path);
            P12KeyStoreAdapter adapter = new P12KeyStoreAdapter(alias, p12Path.toAbsolutePath().toString(), "", "");
            adapter.descriptor().markError("cannot read password.txt: " + e.getMessage());
            return adapter;
        }

        P12KeyStoreAdapter adapter = new P12KeyStoreAdapter(alias, p12Path.toAbsolutePath().toString(), password, password);
        try {
            adapter.engineLoad(null, null);
        } catch (IOException e) {
            // error already recorded on descriptor by engineLoad
            LOG.warnf("[P12Scanner] failed to load %s — alias=%s marked ERROR", p12Path, alias);
        }
        return adapter;
    }

    static String deriveAlias(Path certsDir, Path p12Path) {
        Path relative = certsDir.relativize(p12Path);
        String withoutExt = relative.toString();
        int dot = withoutExt.lastIndexOf('.');
        if (dot > 0) {
            withoutExt = withoutExt.substring(0, dot);
        }
        // normalize to forward slashes, lowercase, replace disallowed chars
        String normalized = withoutExt
            .replace('\\', '/')
            .toLowerCase()
            .replaceAll("[^a-z0-9\\-_./]", "-")
            .replaceAll("-{2,}", "-")
            .replaceAll("(^|/)\\-+", "$1")
            .replaceAll("\\-+(/|$)", "$1");
        return "p12/" + normalized;
    }

    private void bootstrapFromClasspath(String logicalPath, Path targetDir) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> resources = cl.getResources(logicalPath);
            // classpath enumeration finds directory URLs — walk each one
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    Path classpathDir = Paths.get(url.toURI());
                    if (Files.isDirectory(classpathDir) && !classpathDir.equals(targetDir)) {
                        copyIfAbsent(classpathDir, classpathDir, targetDir);
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("[P12Scanner] classpath bootstrap skipped: %s", e.getMessage());
        }
    }

    private void copyIfAbsent(Path classpathRoot, Path sourceDir, Path targetRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".p12") || name.endsWith(".pfx") || name.equals("password.txt");
                })
                .forEach(src -> {
                    Path relative = classpathRoot.relativize(src);
                    Path target = targetRoot.resolve(relative);
                    if (!Files.exists(target)) {
                        try {
                            Files.createDirectories(target.getParent());
                            Files.copy(src, target, StandardCopyOption.COPY_ATTRIBUTES);
                            LOG.infof("[P12Scanner] bootstrapped %s → %s", src, target);
                        } catch (IOException e) {
                            LOG.warnf("[P12Scanner] failed to bootstrap %s: %s", src, e.getMessage());
                        }
                    }
                });
        }
    }
}
