package io.github.origingate.velocity;

import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.LoginAttempt;
import io.github.origingate.core.rules.Rule;
import io.github.origingate.presentation.MessagesRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderTest {
    private static final LoginAttempt ALEX = new LoginAttempt("Alex", UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Addresses.parse("203.0.113.7").orElseThrow(), permission -> false);

    private static String plain(String template, Decision decision) {
        return PlainTextComponentSerializer.plainText().serialize(OriginGateVelocity.render(template, ALEX, decision));
    }

    @Test void placeholdersAreFilledAsPlainText() {
        IpInfo info = new IpInfo("203.0.113.7", "<red>Evil</red> Net", "Org", "Some VPN", "Amsterdam", null, "Netherlands", "NL",
                null, true, false, "Hosting", Instant.EPOCH);
        Decision decision = new Decision(Decision.Outcome.DENY, Rule.VPN, null,
                new LookupService.Result(info, LookupService.Source.PROVIDER), false);
        assertEquals("<red>Evil</red> Net / Some VPN (Netherlands, NL, Amsterdam, -) Alex vpn",
                plain("<yellow><provider> / <organisation></yellow> (<country>, <country_code>, <city>, <region>) <username> <rule>", decision));
    }

    @Test void legacyTextKeepsHexColoursOnlyWhenAsked() {
        Component orange = Component.text("Kicked", TextColor.color(0xff8800));
        assertEquals("\u00a7x\u00a7f\u00a7f\u00a78\u00a78\u00a70\u00a70Kicked", MessagesRenderer.legacy(orange, true));
        assertEquals("\u00a76Kicked", MessagesRenderer.legacy(orange, false), "older targets get the nearest named colour");
    }

    @Test void missingLookupShowsDashes() {
        Decision decision = new Decision(Decision.Outcome.DENY, Rule.LOOKUP_FAILURE, "timeout", null, false);
        assertEquals("- - - lookup-failure 203.0.113.7", plain("<provider> <country> <type> <rule> <ip>", decision));
        assertTrue(plain("<time>", decision).matches("\\d{10}"));
    }
}
