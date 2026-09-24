package io.github.tuskworks.crates.gui;

import io.github.tuskworks.crates.TuskCratesPlugin;
import io.github.tuskworks.crates.crate.Crate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** SMP-style /crates menu: every crate with the player's virtual key balance. */
public final class CratesMenu implements Menu {

    private final TuskCratesPlugin plugin;
    private final Player player;
    private final List<Crate> crates;
    private final Inventory inventory;

    public CratesMenu(TuskCratesPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.crates = plugin.crates().all().stream().filter(Crate::showInMenu).limit(54).toList();
        int rows = Math.max(1, (crates.size() + 8) / 9);
        this.inventory = plugin.getServer().createInventory(this, rows * 9, plugin.messages().get("menu.crates-title"));
    }

    /** Must run on the player's thread. */
    public void open() {
        for (int i = 0; i < crates.size(); i++) {
            Crate crate = crates.get(i);
            int keys = plugin.keyStorage().count(player.getUniqueId(), crate.id());
            ItemStack icon = Items.withExtraLore(crate.keyTemplate(), List.of(
                    plugin.messages().item("menu.crates-keys", Placeholder.unparsed("amount", String.valueOf(keys))),
                    plugin.messages().item("menu.crates-left"),
                    plugin.messages().item("menu.crates-right")));
            icon.setAmount(Math.max(1, Math.min(keys, icon.getMaxStackSize())));
            inventory.setItem(i, icon);
        }
        player.openInventory(inventory);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= crates.size()) {
            return;
        }
        Crate crate = crates.get(slot);
        boolean preview = event.isRightClick();
        // Inventories must not be swapped from inside a click event.
        player.getScheduler().run(plugin, task -> {
            if (preview) {
                new PreviewMenu(plugin, crate).open(player);
            } else {
                player.closeInventory();
                plugin.openService().openVirtual(player, crate);
            }
        }, null);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
