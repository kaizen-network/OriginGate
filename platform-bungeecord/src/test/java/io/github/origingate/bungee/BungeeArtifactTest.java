package io.github.origingate.bungee;

import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BungeeArtifactTest {
    @Test void shipsPluginWithoutProxyApi() throws Exception {
        try (JarFile jar = new JarFile(System.getProperty("origingate.artifact"))) {
            for (String name : new String[] {"bungee.yml", "config.yml", "messages.yml",
                    "io/github/origingate/bungee/OriginGateBungee.class", "io/github/origingate/internal/maxmind17/Reader.class",
                    "io/github/origingate/internal/maxmind8/Reader.class", "META-INF/THIRD_PARTY_NOTICES.md"})
                assertNotNull(jar.getJarEntry(name), name);
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("net/md_5/bungee/")));
        }
    }
}
