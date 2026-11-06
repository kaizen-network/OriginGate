package io.github.origingate.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The shipped JAR may be renamed, deleted, or replaced while the proxy runs; storage and reload must keep working. */
class JarSwapTest {
    private static final String SERVICES = "META-INF/services/io.github.origingate.internal.mariadb.plugin.";
    private static final Path ARTIFACT = Paths.get(System.getProperty("origingate.velocityArtifact"));

    @TempDir Path directory;

    @Test void driverServiceFilesComeFromMemory() throws Exception {
        try (URLClassLoader plugin = plugin(ARTIFACT)) {
            ClassLoader isolated = mariadb(plugin).getClass().getClassLoader();
            assertNotSame(plugin, isolated);
            for (String service : List.of("Codec", "AuthenticationPluginFactory", "TlsSocketPlugin", "CredentialPlugin")) {
                List<URL> found = Collections.list(isolated.getResources(SERVICES + service));
                assertEquals(1, found.size(), service);
                assertEquals("origingate-memory", found.get(0).getProtocol(), "ServiceLoader would open the JAR again by its path");
            }
        }
    }

    /** Windows cannot delete a JAR that is open. */
    @Test @DisabledOnOs(OS.WINDOWS) void storageAndDefaultsWorkAfterTheJarIsDeleted() throws Exception {
        Path jar = Files.copy(ARTIFACT, directory.resolve("plugin.jar"));
        try (URLClassLoader plugin = plugin(jar)) {
            Driver driver = mariadb(plugin);
            Method bundled = Class.forName("io.github.origingate.core.config.ConfigLoader", true, plugin).getMethod("bundled", String.class);
            Files.delete(jar);
            // Nothing listens on port 1, so the driver gets as far as connecting. Before 1.1.1 it failed earlier with
            // ServiceConfigurationError: io.github.origingate.internal.mariadb.plugin.Codec: Error accessing configuration file.
            assertThrows(SQLException.class, () -> driver.connect("jdbc:mariadb://127.0.0.1:1/test?connectTimeout=1000", new Properties()));
            for (String name : List.of("config.yml", "messages.yml")) {
                try (InputStream input = (InputStream) bundled.invoke(null, name)) {
                    assertNotNull(input, name);
                }
            }
        }
    }

    /** The JAR in its own class loader, as the proxy loads a plugin. */
    private static URLClassLoader plugin(Path jar) throws Exception {
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static Driver mariadb(ClassLoader plugin) throws Exception {
        Method mariadb = Class.forName("io.github.origingate.core.storage.IsolatedDrivers", true, plugin).getDeclaredMethod("mariadb");
        mariadb.setAccessible(true);
        return (Driver) mariadb.invoke(null);
    }
}
