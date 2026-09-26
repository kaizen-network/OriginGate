package io.github.origingate.core.rules;

import io.github.origingate.core.lookup.LookupService;

/**
 * The result of a check. {@code rule} is null when no rule matched. {@code lookup} is null when no
 * lookup was needed or it failed. In dry-run mode a DENY decision is logged but {@link #kicks()} is false.
 */
public record Decision(Outcome outcome, Rule rule, String note, LookupService.Result lookup, boolean dryRun) {

    public enum Outcome {
        /** No rule matched, or a global bypass applied. */
        ALLOW,
        /** A rule matched and the player has one of its bypass permissions. */
        BYPASS,
        /** A rule matched. */
        DENY
    }

    public boolean kicks() {
        return outcome == Outcome.DENY && !dryRun;
    }
}
