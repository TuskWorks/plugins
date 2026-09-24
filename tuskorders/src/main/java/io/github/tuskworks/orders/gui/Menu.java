package io.github.tuskworks.orders.gui;

import io.github.tuskworks.orders.TuskOrders;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** A chest menu whose slots are buttons. Items can't be moved unless a subclass allows it. */
public abstract class Menu implements InventoryHolder {

    @FunctionalInterface
    public interface Button {
        void click(Player player, ClickType click);
    }

    protected final TuskOrders plugin;
    protected final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();

    @SuppressWarnings("this-escape") // the inventory only keeps a reference to its holder
    protected Menu(TuskOrders plugin, Player viewer, int rows, Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Fills the inventory; called on open and whenever the contents should refresh. */
    protected abstract void render();

    public void open() {
        render();
        viewer.openInventory(inventory);
    }

    /** Opens another menu on the next tick, which is the safe way to switch from a click handler. */
    protected void switchTo(Menu next) {
        viewer.getScheduler().run(plugin, task -> next.open(), null);
    }

    protected void closeLater() {
        viewer.getScheduler().run(plugin, task -> viewer.closeInventory(), null);
    }

    protected void clear() {
        inventory.clear();
        buttons.clear();
    }

    protected void set(int slot, ItemStack item, Button button) {
        inventory.setItem(slot, item);
        if (button != null) {
            buttons.put(slot, button);
        } else {
            buttons.remove(slot);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected int size() {
        return inventory.getSize();
    }

    void click(Player player, int slot, ClickType click) {
        Button button = buttons.get(slot);
        if (button != null) {
            button.click(player, click);
        }
    }

    /** Whether players may put items into and take items out of this menu. */
    boolean freeSlots() {
        return false;
    }

    void closed(Player player) {
    }
}
