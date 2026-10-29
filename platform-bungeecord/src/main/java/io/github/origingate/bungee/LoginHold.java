package io.github.origingate.bungee;

import java.util.concurrent.atomic.AtomicBoolean;

/** Completion and shutdown can race; each intent must be released exactly once. */
final class LoginHold implements AutoCloseable {
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Runnable release;
    LoginHold(Runnable release) { this.release = release; }
    boolean isOpen() { return open.get(); }
    @Override public void close() { if (open.compareAndSet(true, false)) release.run(); }
}
