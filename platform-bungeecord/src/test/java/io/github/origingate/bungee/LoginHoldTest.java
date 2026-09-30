package io.github.origingate.bungee;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoginHoldTest {
    @Test void shutdownAndCompletionReleaseOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        LoginHold hold = new LoginHold(calls::incrementAndGet);
        hold.close();
        hold.close();
        assertEquals(1, calls.get());
    }
}
