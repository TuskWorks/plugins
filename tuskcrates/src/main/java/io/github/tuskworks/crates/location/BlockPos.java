package io.github.tuskworks.crates.location;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

public record BlockPos(UUID world, int x, int y, int z) {

    public static BlockPos of(Block block) {
        return new BlockPos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    /** The loaded world, or null when it is not loaded. */
    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    /** Center of the block's top face. Requires the world to be loaded. */
    public Location topCenter() {
        return new Location(bukkitWorld(), x + 0.5, y + 1.0, z + 0.5);
    }
}
