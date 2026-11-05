package io.github.origingate.bungee;

import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BungeeArtifactTest {
    @Test void shipsPluginWithoutProxyApi() throws Exception {
        try (JarFile jar = new JarFile(System.getProperty("origingate.artifact"))) {
            for (String name : new String[] {"bungee.yml", "config.yml", "messages.yml",
                    "io/github/origingate/bungee/OriginGateBungee.class", "io/github/origingate/internal/maxmind17/Reader.class",
                    "io/github/origingate/internal/maxmind8/Reader.class", "META-INF/THIRD_PARTY_NOTICES.md",
                    "io/github/origingate/internal/bstats/bungeecord/Metrics.class", "META-INF/licenses/bStats.txt"})
                assertNotNull(jar.getJarEntry(name), name);
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("net/md_5/bungee/")));
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("org/bstats/")));
        }
    }

    /** Only these SQLite natives are shipped, so every JAR fits Hangar's 10 MB limit per file (tools/publish_hangar.py). */
    @Test void keepsOnlyTheListedSqliteNativesAndFitsHangar() throws Exception {
        java.io.File file = new java.io.File(System.getProperty("origingate.artifact"));
        assertTrue(file.length() < 10_000_000, file.length() + " bytes");
        try (JarFile jar = new JarFile(file)) {
            java.util.Set<String> natives = new java.util.TreeSet<>();
            jar.stream().map(java.util.zip.ZipEntry::getName).filter(n -> n.startsWith("org/sqlite/native/") && !n.endsWith("/"))
                    .forEach(n -> natives.add(n.split("/")[3] + "/" + n.split("/")[4]));
            assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList("Linux/aarch64", "Linux/x86_64", "Linux-Musl/aarch64",
                    "Linux-Musl/x86_64", "Mac/aarch64", "Mac/x86_64", "Windows/aarch64", "Windows/x86_64")), natives);
        }
    }
}
