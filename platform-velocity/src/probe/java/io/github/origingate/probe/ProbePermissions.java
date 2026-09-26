package io.github.origingate.probe;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only permission source for the loopback probe. Each line of permissions.txt is
 * "player-name permission". The file is read on every check. Never install this on a real proxy.
 */
@Plugin(id = "origingate-probe-permissions", name = "OriginGate Probe Permissions", version = "1",
        description = "Test-only permissions for the OriginGate probe")
public final class ProbePermissions {
    private final Path file;

    @Inject public ProbePermissions(@DataDirectory Path dataDirectory) {
        this.file = dataDirectory.resolve("permissions.txt");
    }

    @Subscribe public void setup(PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player player)) return;
        String name = player.getUsername();
        event.setProvider(subject -> permission -> granted(name, permission) ? Tristate.TRUE : Tristate.UNDEFINED);
    }

    private boolean granted(String name, String permission) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equalsIgnoreCase(name) && parts[1].equals(permission)) return true;
            }
        } catch (IOException ex) {
            return false;
        }
        return false;
    }
}
