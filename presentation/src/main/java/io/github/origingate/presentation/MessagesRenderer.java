package io.github.origingate.presentation;
import io.github.origingate.core.Text;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.rules.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.time.Instant;

public final class MessagesRenderer {
    private MessagesRenderer() { }
    public static String legacy(String template, LoginAttempt attempt, Decision decision) {
        return LegacyComponentSerializer.legacySection().serialize(render(template, attempt, decision));
    }
    public static Component render(String template, LoginAttempt attempt, Decision decision) {
        IpInfo info = decision.lookup() == null ? null : decision.lookup().info();
        TagResolver placeholders = TagResolver.resolver(
                Placeholder.unparsed("username", attempt.username()),
                Placeholder.unparsed("uuid", attempt.uuid() == null ? "-" : attempt.uuid().toString()),
                Placeholder.unparsed("ip", Addresses.text(attempt.address())),
                Placeholder.unparsed("rule", decision.rule() == null ? "-" : decision.rule().id()),
                Placeholder.unparsed("time", String.valueOf(Instant.now().getEpochSecond())),
                Placeholder.unparsed("provider", Text.dash(info == null ? null : info.provider())),
                Placeholder.unparsed("organisation", Text.dash(info == null ? null : info.displayOrganisation())),
                Placeholder.unparsed("country", Text.dash(info == null ? null : info.country())),
                Placeholder.unparsed("country_code", Text.dash(info == null ? null : info.countryCode())),
                Placeholder.unparsed("city", Text.dash(info == null ? null : info.city())),
                Placeholder.unparsed("region", Text.dash(info == null ? null : info.region())),
                Placeholder.unparsed("type", Text.dash(info == null ? null : info.type())));
        return MiniMessage.miniMessage().deserialize(template, placeholders);
    }
}
