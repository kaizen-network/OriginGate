package io.github.origingate.bukkit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerVersionTest {
    @Test void hexColorsFromMinecraft116() {
        assertFalse(ServerVersion.hexColors("1.7.2-R0.3"));
        assertFalse(ServerVersion.hexColors("1.12.2-R0.1-SNAPSHOT"));
        assertTrue(ServerVersion.hexColors("1.16.5-R0.1-SNAPSHOT"));
        assertTrue(ServerVersion.hexColors("1.21.7-R0.1-SNAPSHOT"));
        assertFalse(ServerVersion.hexColors("unknown"));
    }
}
