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
                    "org/sqlite/JDBC.class", "io/github/origingate/core/rules/Gate.class"}) {
                assertNotNull(jar.getEntry(entry), entry);
            }
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("org/yaml/") || e.getName().startsWith("org/mariadb/")),
                    "libraries are relocated");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("com/maxmind/")), "MaxMind reader is relocated");
            assertTrue(jar.getEntry("module-info.class") == null, "no root module descriptor");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("com/google/gson/")), "Gson is isolated");
            assertTrue(jar.stream().noneMatch(e -> e.getName().startsWith("io/github/origingate/probe/")), "probe plugin is not shipped");
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("com/velocitypowered/")));
        }
    }
}
