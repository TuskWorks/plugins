package io.github.tuskworks.crates.location;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Which blocks are crates, persisted in locations.yml. */
public final class CrateLocations {

    private final File file;
    private final Logger logger;
    private final Map<BlockPos, String> crates = new ConcurrentHashMap<>();

    public CrateLocations(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        crates.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> entry : yaml.getMapList("locations")) {
            try {
                BlockPos pos = new BlockPos(
                        UUID.fromString(String.valueOf(entry.get("world"))),
                        ((Number) entry.get("x")).intValue(),
                        ((Number) entry.get("y")).intValue(),
                        ((Number) entry.get("z")).intValue());
                crates.put(pos, String.valueOf(entry.get("crate")));
            } catch (RuntimeException e) {
                logger.warning("Ignoring malformed entry in locations.yml: " + entry);
            }
        }
    }

    public Optional<String> crateAt(BlockPos pos) {
        return Optional.ofNullable(crates.get(pos));
    }

    public void set(BlockPos pos, String crate) {
        crates.put(pos, crate);
    }

    public boolean remove(BlockPos pos) {
        return crates.remove(pos) != null;
    }

    public Map<BlockPos, String> all() {
        return Map.copyOf(crates);
    }

    public List<BlockPos> inChunk(UUID world, int chunkX, int chunkZ) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : crates.keySet()) {
            if (pos.world().equals(world) && pos.chunkX() == chunkX && pos.chunkZ() == chunkZ) {
                result.add(pos);
            }
        }
        return result;
    }

    public synchronized void save() {
        List<Map<String, Object>> list = new ArrayList<>();
        crates.forEach((pos, crate) -> {
            Map<String, Object> entry = new HashMap<>();
            entry.put("world", pos.world().toString());
            entry.put("x", pos.x());
            entry.put("y", pos.y());
            entry.put("z", pos.z());
            entry.put("crate", crate);
            list.add(entry);
        });
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("locations", list);
        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save locations.yml", e);
        }
    }
}
