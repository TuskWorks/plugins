package io.github.tuskworks.crates.hologram;

import io.github.tuskworks.crates.TuskCratesPlugin;
import io.github.tuskworks.crates.crate.Crate;
import io.github.tuskworks.crates.location.BlockPos;
import io.github.tuskworks.crates.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

/**
 * Crate labels rendered with native text displays. They are never saved to the world;
 * they are spawned again whenever the chunk loads.
 */
public final class HologramService {

    private final TuskCratesPlugin plugin;
    private final NamespacedKey tag;

    public HologramService(TuskCratesPlugin plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "hologram");
    }

    public void refreshAll() {
        plugin.locations().all().keySet().forEach(this::refresh);
    }

    /** Respawns the hologram of a crate block, or removes it when the block is no longer a crate. */
    public void refresh(BlockPos pos) {
        World world = pos.bukkitWorld();
        if (world == null) {
            return;
        }
        plugin.getServer().getRegionScheduler().execute(plugin, world, pos.chunkX(), pos.chunkZ(), () -> {
            if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
                return;
            }
            removeNow(pos);
            if (!plugin.settings().hologramEnabled()) {
                return;
            }
            plugin.locations().crateAt(pos)
                    .flatMap(plugin.crates()::get)
                    .ifPresent(crate -> spawnNow(pos, crate));
        });
    }

    public void onChunkLoad(World world, int chunkX, int chunkZ) {
        plugin.locations().inChunk(world.getUID(), chunkX, chunkZ).forEach(this::refresh);
    }

    /** Best effort cleanup on shutdown; the entities are not persistent anyway. */
    public void removeAll() {
        for (BlockPos pos : plugin.locations().all().keySet()) {
            try {
                if (pos.bukkitWorld() != null && pos.bukkitWorld().isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
                    removeNow(pos);
                }
            } catch (RuntimeException ignored) {
                // Folia refuses cross-region access during shutdown; the entities vanish on their own.
            }
        }
    }

    private void spawnNow(BlockPos pos, Crate crate) {
        if (crate.hologramLines().isEmpty()) {
            return;
        }
        Component text = Component.join(
                net.kyori.adventure.text.JoinConfiguration.newlines(),
                crate.hologramLines().stream()
                        .map(line -> Text.parse(line, Placeholder.component("crate", crate.displayName())))
                        .toList());
        Location location = hologramLocation(pos);
        location.getWorld().spawn(location, TextDisplay.class, display -> {
            display.setPersistent(false);
            display.text(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            display.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void removeNow(BlockPos pos) {
        Location location = hologramLocation(pos);
        for (TextDisplay display : location.getWorld().getNearbyEntitiesByType(TextDisplay.class, location, 0.75)) {
            if (display.getPersistentDataContainer().has(tag)) {
                display.remove();
            }
        }
    }

    private Location hologramLocation(BlockPos pos) {
        return new Location(pos.bukkitWorld(), pos.x() + 0.5, pos.y() + plugin.settings().hologramHeight(), pos.z() + 0.5);
    }
}
