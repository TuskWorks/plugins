package io.github.tuskworks.crates.gui;

import io.github.tuskworks.crates.TuskCratesPlugin;
import io.github.tuskworks.crates.crate.Crate;
import io.github.tuskworks.crates.crate.Reward;
import io.github.tuskworks.crates.open.OpenService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Locale;

/** Read-only list of a crate's rewards with their chances. */
public final class PreviewMenu implements Menu {

    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS = 45;
    private static final int CLOSE = 49;
    private static final int NEXT = 53;

    private final TuskCratesPlugin plugin;
    private final Crate crate;
    private final Inventory inventory;
    private int page;

    public PreviewMenu(TuskCratesPlugin plugin, Crate crate) {
        this.plugin = plugin;
        this.crate = crate;
        this.inventory = plugin.getServer().createInventory(this, 54,
                plugin.messages().get("menu.preview-title", OpenService.crateTag(crate)));
    }

    /** Must run on the player's thread. */
    public void open(Player player) {
        render();
        player.openInventory(inventory);
    }

    private int pages() {
        return Math.max(1, (crate.rewards().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void render() {
        inventory.clear();
        List<Reward> rewards = crate.rewards();
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= rewards.size()) {
                break;
            }
            Reward reward = rewards.get(index);
            String chance = String.format(Locale.ROOT, "%.2f", crate.chancePercent(reward));
            inventory.setItem(i, Items.withExtraLore(reward.displayItem(),
                    List.of(plugin.messages().item("menu.preview-chance", Placeholder.unparsed("chance", chance)))));
        }
        for (int slot = PAGE_SIZE; slot < 54; slot++) {
            inventory.setItem(slot, Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        }
        if (page > 0) {
            inventory.setItem(PREVIOUS, Items.named(Material.ARROW, plugin.messages().item("menu.previous-page")));
        }
        if (page < pages() - 1) {
            inventory.setItem(NEXT, Items.named(Material.ARROW, plugin.messages().item("menu.next-page")));
        }
        inventory.setItem(CLOSE, Items.named(Material.BARRIER, plugin.messages().item("menu.close")));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        switch (event.getRawSlot()) {
            case PREVIOUS -> {
                if (page > 0) {
                    page--;
                    render();
                }
            }
            case NEXT -> {
                if (page < pages() - 1) {
                    page++;
                    render();
                }
            }
            case CLOSE -> {
                var viewer = event.getWhoClicked();
                viewer.getScheduler().run(plugin, task -> viewer.closeInventory(), null);
            }
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
