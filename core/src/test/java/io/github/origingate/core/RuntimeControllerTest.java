package io.github.origingate.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeControllerTest {
    @TempDir Path directory;
    @Test void asyncCloseDoesNotWaitForReloadMonitorAndPreventsAnotherReload() throws Exception {
        RuntimeController controller = new RuntimeController(directory, Log.NONE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread reload = new Thread(() -> {
            synchronized (controller) {
                locked.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        });
        reload.start();
        assertTrue(locked.await(2, TimeUnit.SECONDS));
        try { assertTimeoutPreemptively(Duration.ofMillis(500), controller::closeAsync); }
        finally { release.countDown(); reload.join(2000); }
        assertEquals("OriginGate is stopping.", controller.reload());
        assertNull(controller.runtime());
    }
}
