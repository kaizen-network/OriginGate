package io.github.origingate.bukkit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Features that depend on the Minecraft version, read from Bukkit's version string such as "1.21.7-R0.1-SNAPSHOT". */
final class ServerVersion {
    private static final Pattern MINOR = Pattern.compile("^1\\.(\\d+)");
    private ServerVersion() { }
    /** Spigot and Paper 1.16+ turn §x hex colours in legacy text into real colours; older servers would show other colours. */
    static boolean hexColors(String bukkitVersion) {
        Matcher version = MINOR.matcher(bukkitVersion);
        return version.find() && Integer.parseInt(version.group(1)) >= 16;
    }
}
