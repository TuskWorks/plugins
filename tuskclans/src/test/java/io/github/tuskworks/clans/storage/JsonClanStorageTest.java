package io.github.tuskworks.clans.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanHome;
import io.github.tuskworks.clans.model.ClanMember;
import io.github.tuskworks.clans.model.ClanRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonClanStorageTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path dir;

    @Test
    void roundTrip() throws Exception {
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID ally = UUID.randomUUID();
        Clan clan = new Clan(UUID.randomUUID(), "TUSK", "Tuskers", 123L);
        clan.putMember(new ClanMember(leader, "Alice", ClanRole.LEADER, 1L));
        clan.putMember(new ClanMember(member, "Bob", ClanRole.OFFICER, 2L));
        clan.addAlly(ally);
        clan.home(new ClanHome("world", 1.5, 64, -3.25, 90f, 10f));
        clan.friendlyFire(true);
        clan.open(true);
        clan.kills(7);
        clan.deaths(3);

        JsonClanStorage storage = new JsonClanStorage(dir, LOG);
        storage.save(clan);
        storage.close();

        List<Clan> loaded = new JsonClanStorage(dir, LOG).loadAll();
        assertEquals(1, loaded.size());
        Clan copy = loaded.getFirst();
        assertEquals(clan.id(), copy.id());
        assertEquals("TUSK", copy.tag());
        assertEquals("Tuskers", copy.name());
        assertEquals(123L, copy.createdAt());
        assertEquals(leader, copy.leader().id());
        assertEquals(ClanRole.OFFICER, copy.member(member).orElseThrow().role());
        assertTrue(copy.isAlliedWith(ally));
        assertEquals(clan.home(), copy.home());
        assertTrue(copy.friendlyFire());
        assertTrue(copy.open());
        assertEquals(7, copy.kills());
        assertEquals(3, copy.deaths());
    }

    @Test
    void deleteRemovesFile() throws Exception {
        Clan clan = new Clan(UUID.randomUUID(), "TUSK", "Tuskers", 0L);
        clan.putMember(new ClanMember(UUID.randomUUID(), "Alice", ClanRole.LEADER, 0L));
        JsonClanStorage storage = new JsonClanStorage(dir, LOG);
        storage.save(clan);
        storage.delete(clan.id());
        storage.close();
        assertTrue(new JsonClanStorage(dir, LOG).loadAll().isEmpty());
    }

    @Test
    void corruptFilesAreSkipped() throws Exception {
        Files.writeString(dir.resolve("broken.json"), "{ not json");
        Files.writeString(dir.resolve("leaderless.json"),
                "{\"id\":\"" + UUID.randomUUID() + "\",\"tag\":\"X\",\"name\":\"X\",\"members\":[]}");
        assertTrue(new JsonClanStorage(dir, LOG).loadAll().isEmpty());
    }
}
