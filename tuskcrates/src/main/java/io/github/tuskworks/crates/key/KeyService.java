package io.github.tuskworks.crates.key;

import io.github.tuskworks.crates.crate.Crate;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/** Physical keys are normal items tagged with the crate id in their persistent data. */
public final class KeyService {

    private final NamespacedKey tag;

    public KeyService(Plugin plugin) {
        this.tag = new NamespacedKey(plugin, "key");
    }

    public ItemStack createKey(Crate crate, int amount) {
        ItemStack key = crate.keyTemplate();
        key.editMeta(meta -> meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, crate.id()));
        key.setAmount(Math.max(1, Math.min(amount, key.getMaxStackSize())));
        return key;
    }

    public Optional<String> crateOf(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(tag, PersistentDataType.STRING));
    }

    public boolean isKey(ItemStack item) {
        return crateOf(item).isPresent();
    }

    public boolean isKeyFor(ItemStack item, Crate crate) {
        return crateOf(item).filter(crate.id()::equals).isPresent();
    }
}
