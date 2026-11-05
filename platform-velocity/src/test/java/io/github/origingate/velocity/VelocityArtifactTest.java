package io.github.origingate.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the shaded JAR that is actually shipped. */
class VelocityArtifactTest {
    @Test void shadedJarHasDescriptorRelocationsAndNotices() throws IOException {
        String path = System.getProperty("origingate.velocityArtifact");
        assertNotNull(path, "run through Gradle");
        try (JarFile jar = new JarFile(path)) {
            JsonObject plugin = JsonParser.parseString(new String(jar.getInputStream(jar.getEntry("velocity-plugin.json"))
                    .readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("origingate", plugin.get("id").getAsString());
            assertEquals("io.github.origingate.velocity.OriginGateVelocity", plugin.get("main").getAsString());
            JsonArray dependencies = plugin.has("dependencies") ? plugin.getAsJsonArray("dependencies") : new JsonArray();
            assertEquals(0, dependencies.size(), "no required plugin dependencies");
            for (String entry : new String[] {"config.yml", "messages.yml", "META-INF/LICENSE", "META-INF/THIRD_PARTY_NOTICES.md",
                    "META-INF/licenses/SnakeYAML.txt", "META-INF/licenses/MariaDB-Connector-J.txt",
                    "META-INF/licenses/MaxMind-DB-Reader.txt",
                    "io/github/origingate/internal/snakeyaml/Yaml.class", "io/github/origingate/internal/mariadb/Driver.class",
                    "io/github/origingate/internal/maxmind17/Reader.class",
                    "io/github/origingate/internal/gson/Gson.class",
                    "io/github/origingate/internal/bstats/velocity/Metrics$Factory.class", "META-INF/licenses/bStats.txt",
                    "org/sqlite/JDBC.class", "io/github/origingate/core/rules/Gate.class"}) {
                assertNotNull(jar.getEntry(entry), entry);
            }
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("org/yaml/") || e.getName().startsWith("org/mariadb/")),
                    "libraries are relocated");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("com/maxmind/")), "MaxMind reader is relocated");
            assertTrue(jar.getEntry("module-info.class") == null, "no root module descriptor");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("com/google/gson/")), "Gson is isolated");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("org/bstats/")), "bStats is relocated");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("net/kyori/")), "Adventure comes from the proxy");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("io/github/origingate/internal/maxmind8/")),
                    "the Java 8 MaxMind reader is not shipped for Java 21");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("io/github/origingate/probe/")), "probe plugin is not shipped");
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("com/velocitypowered/")));
        }
    }

    /** Only these SQLite natives are shipped, so every JAR fits Hangar's 10 MB upload limit (tools/publish_hangar.py). */
    @Test void keepsOnlyTheListedSqliteNativesAndFitsHangar() throws Exception {
        java.io.File file = new java.io.File(System.getProperty("origingate.velocityArtifact"));
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
