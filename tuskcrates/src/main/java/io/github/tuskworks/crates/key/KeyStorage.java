package io.github.tuskworks.crates.key;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Virtual key balances, one YAML file per player. Every mutation runs inside
 * {@link ConcurrentHashMap#compute} so it is atomic across Folia region threads.
 */
public final class KeyStorage {

    private final File folder;
    private final Logger logger;
    private final Predicate<UUID> isOnline;
    private final ConcurrentHashMap<UUID, Balance> cache = new ConcurrentHashMap<>();

    public KeyStorage(File folder, Logger logger, Predicate<UUID> isOnline) {
        this.folder = folder;
        this.logger = logger;
        this.isOnline = isOnline;
    }

    public int count(UUID player, String crate) {
        return cache.computeIfAbsent(player, this::load).count(crate);
    }

    public Map<String, Integer> all(UUID player) {
        return cache.computeIfAbsent(player, this::load).snapshot();
    }

    public void add(UUID player, String crate, int amount) {
        if (amount <= 0) {
            return;
        }
        cache.compute(player, (id, balance) -> {
            Balance b = balance == null ? load(id) : balance;
            b.change(crate, amount);
            return b;
        });
    }

    /** Removes up to {@code amount} keys and returns how many were actually removed. */
    public int remove(UUID player, String crate, int amount) {
        int[] removed = {0};
        cache.compute(player, (id, balance) -> {
            Balance b = balance == null ? load(id) : balance;
            removed[0] = Math.min(amount, b.count(crate));
            b.change(crate, -removed[0]);
            return b;
        });
        return removed[0];
    }

    /** Takes exactly {@code amount} keys, or none if the player has fewer. */
    public boolean take(UUID player, String crate, int amount) {
        boolean[] taken = {false};
        cache.compute(player, (id, balance) -> {
            Balance b = balance == null ? load(id) : balance;
            if (b.count(crate) >= amount) {
                b.change(crate, -amount);
                taken[0] = true;
            }
            return b;
        });
        return taken[0];
    }

    /** Warms the cache off the main thread, e.g. during async pre-login. */
    public void preload(UUID player) {
        cache.computeIfAbsent(player, this::load);
    }

    /**
     * Writes changed balances and evicts offline players whose data is already on disk.
     * Safe to call from an async thread.
     */
    public void saveDirty() {
        Map<UUID, Map<String, Integer>> toWrite = new HashMap<>();
        for (UUID id : List.copyOf(cache.keySet())) {
            cache.computeIfPresent(id, (key, balance) -> {
                if (balance.dirty) {
                    toWrite.put(key, balance.snapshot());
                    balance.dirty = false;
                    return balance;
                }
                return isOnline.test(key) ? balance : null;
            });
        }
        toWrite.forEach(this::write);
    }

    public void saveAll() {
        cache.forEach((id, balance) -> {
            write(id, balance.snapshot());
            balance.dirty = false;
        });
    }

    private Balance load(UUID player) {
        Balance balance = new Balance();
        File file = file(player);
        if (file.exists()) {
            ConfigurationSection keys = YamlConfiguration.loadConfiguration(file).getConfigurationSection("keys");
            if (keys != null) {
                for (String crate : keys.getKeys(false)) {
                    int amount = keys.getInt(crate);
                    if (amount > 0) {
                        balance.keys.put(crate, amount);
                    }
                }
            }
        }
        return balance;
    }

    private void write(UUID player, Map<String, Integer> keys) {
        YamlConfiguration yaml = new YamlConfiguration();
        keys.forEach((crate, amount) -> yaml.set("keys." + crate, amount));
        File target = file(player);
        try {
            Files.createDirectories(folder.toPath());
            File temp = new File(folder, player + ".yml.tmp");
            yaml.save(temp);
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some file systems (e.g. certain network mounts) can't rename atomically
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save virtual keys of " + player, e);
        }
    }

    private File file(UUID player) {
        return new File(folder, player + ".yml");
    }

    /** Guarded by the owning map entry's compute lock for writes. */
    private static final class Balance {
        private final Map<String, Integer> keys = new ConcurrentHashMap<>();
        private volatile boolean dirty;

        int count(String crate) {
            return keys.getOrDefault(crate, 0);
        }

        void change(String crate, int delta) {
            if (delta == 0) {
                return;
            }
            keys.merge(crate, delta, (a, b) -> a + b > 0 ? a + b : null);
            dirty = true;
        }

        Map<String, Integer> snapshot() {
            return Map.copyOf(keys);
        }
    }
}
