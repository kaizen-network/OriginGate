package io.github.origingate.core;

import io.github.origingate.core.lists.ListEntry;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.LoginAttempt;
import io.github.origingate.core.rules.Rule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The allow and deny commands against a real runtime on SQLite. */
class ListCommandsTest {
    private static final String ALLOW_USAGE =
            "Usage: /origingate allow add <player|ip|cidr> [duration] [note], remove <player|ip|cidr>, list [page]";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    @TempDir Path directory;
    private OriginGateRuntime runtime;
    private final List<Kick> kicks = new CopyOnWriteArrayList<>();
    private volatile boolean kickFails;

    @AfterEach void stop() {
        if (runtime != null) runtime.close();
    }

    private record Kick(Predicate<LoginAttempt> who, Decision decision) { }

    private void load(Object... changes) throws Exception {
        TestSupport.writeConfig(directory, changes);
        runtime = OriginGateRuntime.load(directory, Log.NONE);
    }

    private Commands commands() {
        return new Commands(new Commands.Platform() {
            @Override public OriginGateRuntime runtime() { return runtime; }
            @Override public Optional<LoginAttempt> onlinePlayer(String name) { return Optional.empty(); }
            @Override public List<String> onlinePlayerNames() { return List.of("Alex"); }
            @Override public String reload() { return "reloaded"; }
            @Override public void kick(Predicate<LoginAttempt> who, Decision decision) {
                kicks.add(new Kick(who, decision));
                if (kickFails) throw new IllegalStateException("kick failed (test)");
            }
        });
    }

    private static final class Replies implements Commands.Sender {
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final Set<String> permissions;

        Replies(String... permissions) { this.permissions = Set.of(permissions); }

        @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }

        @Override public void reply(String line) { lines.add(line); }

        String next() throws InterruptedException {
            String line = lines.poll(10, TimeUnit.SECONDS);
            assertNotNull(line, "no reply");
            return line;
        }

        String waitFor(String text) throws InterruptedException {
            List<String> seen = new ArrayList<>();
            while (true) {
                String line = next();
                seen.add(line);
                if (line.contains(text)) return line;
                assertTrue(seen.size() < 50, "no reply containing " + text + "; saw " + seen);
            }
        }
    }

    private Replies run(Replies sender, String... args) {
        commands().execute(args, sender);
        return sender;
    }

    private static Replies both() { return new Replies(Commands.ALLOW, Commands.DENY); }

    private static String time(Instant instant) { return TIME.format(instant.atZone(ZoneId.systemDefault())); }

    @Test void addWithDurationAndNote() throws Exception {
        load();
        Replies sender = run(both(), "allow", "add", "Kai", "7d", "trusted", "tester");
        String reply = sender.waitFor("Added kai");
        ListEntry stored = runtime.lists().active(ListEntry.Kind.ALLOW).join().get(0);
        assertEquals("Added kai to the allow list, expires " + time(stored.expiresAt()) + ", note: trusted tester.", reply);
        assertTrue(Duration.between(Instant.now(), stored.expiresAt()).toDays() >= 6);
        assertEquals("console", stored.addedBy());

        run(sender, "allow", "list");
        sender.waitFor("Allow list, page 1 of 1 (1 entries):");
        String line = sender.next();
        assertTrue(line.contains("kai") && line.contains("by console") && line.contains("trusted tester"), line);
    }

    @Test void noteWithoutDurationNeverExpires() throws Exception {
        load();
        Replies sender = run(both(), "deny", "add", "203.0.113.7", "spam", "bot");
        assertEquals("Added 203.0.113.7 to the deny list, never expires, note: spam bot.", sender.waitFor("Added"));
        assertNull(runtime.lists().active(ListEntry.Kind.DENY).join().get(0).expiresAt());
    }

    @Test void denyAddKicksMatchingOnlinePlayers() throws Exception {
        load();
        Replies sender = run(both(), "deny", "add", "203.0.113.7", "spam");
        sender.waitFor("Added");
        assertEquals(1, kicks.size());
        Kick kick = kicks.get(0);
        assertTrue(kick.who().test(TestSupport.player("Alex", "203.0.113.7")));
        assertTrue(!kick.who().test(TestSupport.player("Alex", "192.0.2.10")));
        assertEquals(Decision.Outcome.DENY, kick.decision().outcome());
        assertEquals(Rule.DENY_LIST, kick.decision().rule());

        run(sender, "allow", "add", "203.0.113.8");
        sender.waitFor("Added");
        assertEquals(1, kicks.size());
    }

    @Test void denyAddSparesPlayersTheLoginWouldLetIn() throws Exception {
        load("bypass.players", List.of("lee"));
        runtime.lists().add(new ListEntry(ListEntry.Kind.ALLOW, "kai", null, null, "console", Instant.now())).join();
        Replies sender = run(both(), "deny", "add", "203.0.113.0/24");
        sender.waitFor("Added");
        Predicate<LoginAttempt> who = kicks.get(0).who();
        assertTrue(!who.test(TestSupport.player("Kai", "203.0.113.7")));
        assertTrue(!who.test(TestSupport.player("Lee", "203.0.113.9")));
        assertTrue(who.test(TestSupport.player("Alex", "203.0.113.8")));
    }

    @Test void denyAddInDryRunKicksNobody() throws Exception {
        load("dry-run", true);
        Replies sender = run(both(), "deny", "add", "Kai");
        assertTrue(sender.waitFor("Added").endsWith("Dry run is on: nobody online is disconnected."));
        assertTrue(kicks.isEmpty());
    }

    @Test void removeOnlyFromNamedList() throws Exception {
        load();
        Replies sender = run(both(), "allow", "add", "kai");
        sender.waitFor("Added");
        run(sender, "deny", "remove", "kai");
        sender.waitFor("kai is not on the deny list.");
        run(sender, "allow", "remove", "Kai");
        sender.waitFor("Removed kai from the allow list.");
        assertEquals(0, runtime.lists().count(ListEntry.Kind.ALLOW));
    }

    @Test void rejectsBadInput() throws Exception {
        load();
        Replies sender = both();
        run(sender, "allow", "add", "two words?");
        assertEquals("two words? is not a player name, UUID, IP address, or CIDR range", sender.next());
        run(sender, "allow", "add", "Kai", "0d");
        assertEquals("Durations go from 1s to 3650d, or never.", sender.next());
        run(sender, "allow", "add", "Kai", "99999d");
        assertEquals("Durations go from 1s to 3650d, or never.", sender.next());
        run(sender, "allow", "add", "Kai", "99999999999999999999d");
        assertEquals("Durations go from 1s to 3650d, or never.", sender.next());
        run(sender, "allow", "add", "Kai", "never", "x".repeat(256));
        assertEquals("Notes can be up to 255 characters.", sender.next());
        run(sender, "allow", "add");
        assertEquals(ALLOW_USAGE, sender.next());
        run(sender, "allow");
        assertEquals(ALLOW_USAGE, sender.next());
        run(sender, "allow", "remove");
        assertEquals(ALLOW_USAGE, sender.next());
        run(sender, "allow", "list", "x");
        assertEquals(ALLOW_USAGE, sender.next());
        run(sender, "deny", "add");
        assertEquals(ALLOW_USAGE.replace("allow", "deny"), sender.next());
        assertEquals(0, runtime.lists().count(ListEntry.Kind.ALLOW));
    }

    @Test void acceptsLongestDurationAndNever() throws Exception {
        load();
        Replies sender = run(both(), "allow", "add", "Kai", "3650d");
        sender.waitFor("Added");
        run(sender, "allow", "add", "Alex", "never", "forever");
        assertEquals("Added alex to the allow list, never expires, note: forever.", sender.waitFor("Added"));
    }

    @Test void permissionsAreSeparate() throws Exception {
        load();
        Replies sender = run(new Replies(Commands.ALLOW), "deny", "add", "kai");
        assertEquals("You do not have permission to use this command.", sender.next());
        assertTrue(Commands.PERMISSIONS.contains(Commands.ALLOW) && Commands.PERMISSIONS.contains(Commands.DENY));
        assertEquals(0, runtime.lists().count(ListEntry.Kind.DENY));
    }

    @Test void pagesTenPerPage() throws Exception {
        load();
        for (int i = 0; i < 12; i++) {
            String target = String.format("player%02d", i);
            runtime.lists().add(new ListEntry(ListEntry.Kind.ALLOW, target, null, null, "console", Instant.now().plusSeconds(i))).join();
        }
        Replies sender = run(both(), "allow", "list", "2");
        sender.waitFor("Allow list, page 2 of 2 (12 entries):");
        assertTrue(sender.next().contains("player10"));
        assertTrue(sender.next().contains("player11"));
        assertNull(sender.lines.poll(200, TimeUnit.MILLISECONDS));
        run(sender, "allow", "list", "3");
        assertEquals("There is no page 3. The allow list has 2 pages.", sender.next());
        run(sender, "deny", "list");
        assertEquals("The deny list is empty.", sender.next());
    }

    @Test void storageErrorIsReported() throws Exception {
        load();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data/origingate.db"));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE origingate_list");
        }
        Replies sender = run(both(), "allow", "add", "kai");
        sender.waitFor("Could not change the allow list: ");
        assertTrue(kicks.isEmpty());
    }

    @Test void suggestsSubcommandsAndTargets() throws Exception {
        load();
        runtime.lists().add(new ListEntry(ListEntry.Kind.DENY, "kai", null, null, "console", Instant.now())).join();
        runtime.lists().add(new ListEntry(ListEntry.Kind.DENY, "203.0.113.7", null, null, "console", Instant.now())).join();
        Replies sender = both();
        assertTrue(commands().suggest(new String[] {"allow"}, sender).contains("allow"));
        assertEquals(List.of("remove"), commands().suggest(new String[] {"allow", "r"}, sender));
        assertEquals(List.of("add", "remove", "list"), commands().suggest(new String[] {"deny", ""}, sender));
        List<String> targets = commands().suggest(new String[] {"deny", "remove", ""}, sender);
        assertEquals(Set.of("kai", "203.0.113.7"), Set.copyOf(targets));
        assertEquals(List.of("Alex"), commands().suggest(new String[] {"deny", "add", "a"}, sender));
        assertTrue(commands().suggest(new String[] {"deny", "remove", ""}, new Replies(Commands.ALLOW)).isEmpty());
    }

    @Test void memoryOnlyReplySaysSo() throws Exception {
        // A folder where the SQLite file should be makes storage fall back to memory.
        Files.createDirectories(directory.resolve("data/origingate.db"));
        load();
        Replies sender = run(both(), "allow", "add", "kai");
        assertTrue(sender.waitFor("Added").endsWith(" Kept in memory only until a restart."));
    }

    private OriginGateRuntime reload() throws Exception {
        OriginGateRuntime next = OriginGateRuntime.load(directory, Log.NONE, new io.github.origingate.core.lookup.LookupStats(),
                Alerts.NONE, false, io.github.origingate.core.rules.OnlineCount.NONE, runtime.lists());
        runtime.close();
        runtime = next;
        return next;
    }

    @Test void memoryOnlyEntriesSurviveAReload() throws Exception {
        Files.createDirectories(directory.resolve("data/origingate.db"));
        load();
        run(both(), "deny", "add", "kai").waitFor("Added");
        reload();
        assertEquals(1, runtime.lists().count(ListEntry.Kind.DENY));
    }

    @Test void unreadableTableAtReloadKeepsTheEntries() throws Exception {
        load();
        run(both(), "deny", "add", "kai").waitFor("Added");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data/origingate.db"));
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE origingate_list RENAME COLUMN note TO note_old");
        }
        reload();
        assertEquals(1, runtime.lists().count(ListEntry.Kind.DENY));
    }

    @Test void persistentListsRunARefreshThreadThatStopsOnClose() throws Exception {
        load();
        assertTrue(listThreads() >= 1);
        runtime.close();
        for (int i = 0; i < 50 && listThreads() > 0; i++) Thread.sleep(100);
        assertEquals(0, listThreads());
    }

    private static long listThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("OriginGate Lists") && t.isAlive()).count();
    }

    @Test void denyAddReplyComesEvenWhenTheKickThrows() throws Exception {
        load();
        kickFails = true;
        assertTrue(run(both(), "deny", "add", "203.0.113.7").waitFor("Added").startsWith("Added 203.0.113.7 to the deny list"));
    }

    @Test void aThrowingKickCheckDoesNotDisconnect() throws Exception {
        load();
        run(both(), "deny", "add", "203.0.113.7").waitFor("Added");
        assertTrue(!kicks.get(0).who().test(null));
    }
}
