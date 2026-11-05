package io.github.origingate.core;

/**
 * Staff alerts about lookups, such as a provider that stopped answering. Each problem is sent once, and once more
 * when it is over, so a long outage does not repeat itself. Called from worker threads.
 */
public interface Alerts {
    void problem(String message);

    void recovered(String message);

    Alerts NONE = new Alerts() {
        @Override public void problem(String message) { }
        @Override public void recovered(String message) { }
    };
}
