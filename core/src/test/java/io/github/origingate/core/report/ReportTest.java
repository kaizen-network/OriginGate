package io.github.origingate.core.report;

import io.github.origingate.core.TestSupport;
import io.github.origingate.core.TestSupport.MutableClock;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {
    @TempDir Path directory;

    @Test void lineHasOneFieldPerValueAndEscapesQuotes() {
        IpInfo info = new IpInfo("203.0.113.7", "Say \"hi\" Net", "Org", "Some VPN", "City", "Region", "Netherlands", "NL",
                "AS64500", true, false, "Hosting", TestSupport.NOW);
        Decision decision = new Decision(Decision.Outcome.DENY, Rule.VPN, "flagged as VPN",
                new LookupService.Result(info, LookupService.Source.PROVIDER), false);
        String line = DecisionLine.format(TestSupport.player("Alex", "203.0.113.7"), decision);
        assertTrue(line.startsWith("DENY rule=vpn player=Alex uuid="), line);
        assertTrue(line.contains(" ip=203.0.113.7 provider=\"Say \\\"hi\\\" Net\" organisation=\"Some VPN\""), line);
        assertTrue(line.contains(" country_code=NL "), line);
        assertTrue(line.contains(" vpn=yes proxy=no source=provider note=\"flagged as VPN\""), line);
        assertTrue(!line.contains("\n"));
    }

    @Test void lineWithoutLookup() {
        Decision decision = new Decision(Decision.Outcome.ALLOW, null, "private address, lookup skipped", null, true);
        String line = DecisionLine.format(TestSupport.player("Alex", "192.168.1.2"), decision);
        assertTrue(line.startsWith("ALLOW rule=- player=Alex"), line);
        assertTrue(line.endsWith("ip=192.168.1.2 note=\"private address, lookup skipped\""), line);
    }

    @Test void dailyFilesAndCleanup() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.NOW);
        DecisionFile file = new DecisionFile(directory, clock);
        file.write("first");
        clock.advance(Duration.ofDays(2));
        file.write("second");
        Files.writeString(directory.resolve("notes.log"), "not ours");
        try (var files = Files.list(directory)) {
            assertEquals(3, files.count());
        }
        assertTrue(Files.readString(directory.resolve("2026-09-26.log")).contains("] first"));
        assertEquals(0, file.deleteOlderThan(2));
        assertEquals(1, file.deleteOlderThan(1));
        assertTrue(Files.exists(directory.resolve("2026-09-28.log")));
        assertTrue(Files.exists(directory.resolve("notes.log")));
    }
}
