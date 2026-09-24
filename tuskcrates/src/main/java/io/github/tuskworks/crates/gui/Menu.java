package io.github.tuskworks.crates.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

/** A plugin-owned inventory. All clicks are cancelled before {@link #onClick} is called. */
public interface Menu extends InventoryHolder {

    default void onClick(InventoryClickEvent event) {
    }

    default void onClose(InventoryCloseEvent event) {
    }
}
