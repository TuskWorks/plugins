package io.github.tuskworks.orders.gui;

import io.github.tuskworks.orders.TuskOrders;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Yes/no screen used before anything that moves money. */
public final class ConfirmMenu extends Menu {

    private final ItemStack summary;
    private final Consumer<Player> onConfirm;
    private final Consumer<Player> onCancel;
    private boolean answered;

    public ConfirmMenu(TuskOrders plugin, Player viewer, Component title, ItemStack summary,
                       Consumer<Player> onConfirm, Consumer<Player> onCancel) {
        super(plugin, viewer, 3, title);
        this.summary = summary;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected void render() {
        clear();
        for (int slot = 0; slot < size(); slot++) {
            set(slot, Icons.filler());
        }
        set(13, summary);
        set(11, Icons.icon(Material.LIME_STAINED_GLASS_PANE, plugin.messages().item("gui.confirm.accept", Map.of())),
                (player, click) -> answer(player, true));
        set(15, Icons.icon(Material.RED_STAINED_GLASS_PANE, plugin.messages().item("gui.confirm.deny", Map.of())),
                (player, click) -> answer(player, false));
    }

    private void answer(Player player, boolean yes) {
        if (answered) {
            return;
        }
        answered = true;
        closeLater();
        (yes ? onConfirm : onCancel).accept(player);
    }
}
