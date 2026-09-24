package io.github.tuskworks.crates.key;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyStorageTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path dir;

    @Test
    void takeNeverGoesNegative() {
        var storage = new KeyStorage(dir.toFile(), LOG, id -> true);
        UUID player = UUID.randomUUID();
        storage.add(player, "rare", 2);
        assertTrue(storage.take(player, "rare", 1));
        assertTrue(storage.take(player, "rare", 1));
        assertFalse(storage.take(player, "rare", 1));
        assertEquals(0, storage.count(player, "rare"));
    }

    @Test
    void removeReportsHowManyWereRemoved() {
        var storage = new KeyStorage(dir.toFile(), LOG, id -> true);
        UUID player = UUID.randomUUID();
        storage.add(player, "common", 3);
        assertEquals(3, storage.remove(player, "common", 10));
        assertEquals(0, storage.count(player, "common"));
    }

    @Test
    void balancesSurviveARestart() {
        UUID player = UUID.randomUUID();
        var first = new KeyStorage(dir.toFile(), LOG, id -> true);
        first.add(player, "legendary", 5);
        first.take(player, "legendary", 2);
        first.saveAll();

        var second = new KeyStorage(dir.toFile(), LOG, id -> true);
        assertEquals(3, second.count(player, "legendary"));
    }

    @Test
    void offlinePlayersAreEvictedOnlyAfterTheirDataIsWritten() {
        UUID player = UUID.randomUUID();
        var storage = new KeyStorage(dir.toFile(), LOG, id -> false);
        storage.add(player, "rare", 4);
        storage.saveDirty(); // writes, keeps in cache
        storage.saveDirty(); // clean + offline: evicted
        assertEquals(4, storage.count(player, "rare")); // reloaded from disk
    }

    @Test
    void concurrentTakesNeverOversell() throws Exception {
        var storage = new KeyStorage(dir.toFile(), LOG, id -> true);
        UUID player = UUID.randomUUID();
        storage.add(player, "rare", 1_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            results.add(pool.submit(() -> {
                int taken = 0;
                for (int i = 0; i < 500; i++) {
                    if (storage.take(player, "rare", 1)) {
                        taken++;
                    }
                }
                return taken;
            }));
        }
        int total = 0;
        for (Future<Integer> result : results) {
            total += result.get();
        }
        pool.shutdown();
        assertEquals(1_000, total);
        assertEquals(0, storage.count(player, "rare"));
    }
}
