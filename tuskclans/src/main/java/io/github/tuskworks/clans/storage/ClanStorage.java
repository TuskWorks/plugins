package io.github.tuskworks.clans.storage;

import io.github.tuskworks.clans.model.Clan;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public interface ClanStorage {

    List<Clan> loadAll() throws IOException;

    /** Snapshots the clan on the calling thread; the write itself may happen later. */
    void save(Clan clan);

    void delete(UUID clanId);

    /** Blocks until pending writes are done. */
    void close();
}
