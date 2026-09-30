package io.github.origingate.bukkit;

import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitArtifactTest {
    @Test void shipsLegacyBytecodeAndIsolatedLibraries() throws Exception {
        try (JarFile jar = new JarFile(System.getProperty("origingate.artifact"))) {
            java.util.Set<String> paths = new java.util.HashSet<>();
            jar.stream().forEach(entry -> assertTrue(paths.add(entry.getName()), "Duplicate entry: " + entry.getName()));
            for (String name : new String[] {"plugin.yml", "config.yml", "messages.yml",
                    "io/github/origingate/bukkit/OriginGateBukkit.class", "io/github/origingate/internal/snakeyaml/Yaml.class",
                    "io/github/origingate/internal/gson/Gson.class", "io/github/origingate/internal/maxmind8/Reader.class",
                    "io/github/origingate/internal/maxmind17/Reader.class", "META-INF/THIRD_PARTY_NOTICES.md"})
                assertNotNull(jar.getJarEntry(name), name);
            for (String name : new String[] {"io/github/origingate/bukkit/OriginGateBukkit.class", "io/github/origingate/core/rules/Gate.class"}) {
                byte[] bytes = jar.getInputStream(jar.getEntry(name)).readNBytes(8);
                assertEquals(52, ((bytes[6] & 255) << 8) | (bytes[7] & 255));
            }
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("org/bukkit/")));
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("com/google/gson/")));
        }
    }
}
