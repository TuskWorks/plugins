package io.github.tuskworks.crates.listener;

import io.github.tuskworks.crates.TuskCratesPlugin;
import io.github.tuskworks.crates.crate.Crate;
import io.github.tuskworks.crates.gui.PreviewMenu;
import io.github.tuskworks.crates.location.BlockPos;
import io.github.tuskworks.crates.open.OpenService;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

public final class CrateBlockListener implements Listener {

    private final TuskCratesPlugin plugin;

    public CrateBlockListener(TuskCratesPlugin plugin) {
        this.plugin = plugin;
    }

    // Not ignoreCancelled: protection plugins usually deny block use at spawn, where crates live.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        BlockPos pos = BlockPos.of(block);
        Optional<Crate> crate = plugin.locations().crateAt(pos).flatMap(plugin.crates()::get);
        if (crate.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && player.isSneaking()
                && player.hasPermission("tuskcrates.admin")) {
            return; // let admins break the crate
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            plugin.openService().openAtBlock(player, crate.get(), pos);
        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK && player.hasPermission("tuskcrates.use")) {
            new PreviewMenu(plugin, crate.get()).open(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        BlockPos pos = BlockPos.of(event.getBlock());
        Optional<String> crateId = plugin.locations().crateAt(pos);
        if (crateId.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("tuskcrates.admin") || !player.isSneaking()) {
            event.setCancelled(true);
            if (player.hasPermission("tuskcrates.admin")) {
                plugin.messages().send(player, "admin.break-hint");
            }
            return;
        }
        plugin.locations().remove(pos);
        plugin.saveLocationsAsync();
        plugin.holograms().refresh(pos);
        plugin.crates().get(crateId.get()).ifPresent(crate ->
                plugin.messages().send(player, "admin.crate-removed", OpenService.crateTag(crate)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isCrate);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isCrate);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (anyCrate(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (anyCrate(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceKey(BlockPlaceEvent event) {
        if (plugin.keys().isKey(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCraftWithKey(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (plugin.keys().isKey(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.holograms().onChunkLoad(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    private boolean isCrate(Block block) {
        return plugin.locations().crateAt(BlockPos.of(block)).isPresent();
    }

    private boolean anyCrate(List<Block> blocks) {
        return blocks.stream().anyMatch(this::isCrate);
    }
}
