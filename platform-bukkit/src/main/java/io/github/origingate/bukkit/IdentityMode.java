package io.github.origingate.bukkit;

import io.github.origingate.core.OfflineModeWarning.Mode;
import org.bukkit.Server;

import java.lang.reflect.Field;

/**
 * Finds out how this server learns who a player is. Proxy settings are not in the Bukkit API this plugin compiles
 * against, so they are read by reflection: Paper's proxies settings (1.19 and newer, then the older PaperConfig),
 * and Spigot's {@code settings.bungeecord}.
 */
final class IdentityMode {
    private IdentityMode() { }

    static Mode of(Server server) {
        if (server.getOnlineMode()) return Mode.ONLINE;
        try {
            Object proxies = paperProxies();
            Boolean velocity;
            Boolean velocityOnline;
            Boolean bungeeOnline;
            if (proxies != null) {
                Object velocitySettings = field(proxies, "velocity");
                velocity = (Boolean) field(velocitySettings, "enabled");
                velocityOnline = (Boolean) field(velocitySettings, "onlineMode");
                bungeeOnline = (Boolean) field(field(proxies, "bungeeCord"), "onlineMode");
            } else {
                Class<?> legacy = find("com.destroystokyo.paper.PaperConfig");
                velocity = legacy == null ? Boolean.FALSE : (Boolean) staticField(legacy, "velocitySupport", Boolean.FALSE);
                velocityOnline = legacy == null ? null : (Boolean) staticField(legacy, "velocityOnlineMode", null);
                bungeeOnline = legacy == null ? null : (Boolean) staticField(legacy, "bungeeOnlineMode", null);
            }
            if (Boolean.TRUE.equals(velocity)) return Boolean.FALSE.equals(velocityOnline) ? Mode.OFFLINE : Mode.MODERN_FORWARDING;
            Class<?> spigot = find("org.spigotmc.SpigotConfig");
            // CraftBukkit without Spigot has no proxy support, so offline mode there means offline.
            boolean bungee = spigot != null && Boolean.TRUE.equals(staticField(spigot, "bungee", Boolean.FALSE));
            if (bungee) return Boolean.FALSE.equals(bungeeOnline) ? Mode.OFFLINE : Mode.LEGACY_FORWARDING;
            return Mode.OFFLINE;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            return Mode.UNKNOWN;
        }
    }

    /** Paper 1.19 and newer: GlobalConfiguration.get().proxies, or null on other servers. */
    private static Object paperProxies() throws ReflectiveOperationException {
        Class<?> global = find("io.papermc.paper.configuration.GlobalConfiguration");
        if (global == null) return null;
        Object settings = global.getMethod("get").invoke(null);
        return settings == null ? null : field(settings, "proxies");
    }

    private static Class<?> find(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getField(name);
        return field.get(owner);
    }

    /** {@code fallback} when the class has no such field, as in newer Paper where PaperConfig is empty. */
    private static Object staticField(Class<?> owner, String name, Object fallback) throws IllegalAccessException {
        try {
            return owner.getField(name).get(null);
        } catch (NoSuchFieldException ex) {
            return fallback;
        }
    }
}
