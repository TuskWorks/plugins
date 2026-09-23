package io.github.tuskworks.orders.gui;

import io.github.tuskworks.orders.TuskOrders;
import io.github.tuskworks.orders.item.ItemRules;
import io.github.tuskworks.orders.model.Order;
import io.github.tuskworks.orders.service.OrderService;
import io.github.tuskworks.orders.service.Outcome;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * An empty chest the player fills with items for one order. Nothing happens until it is
 * closed: then matching items are handed in and paid for, and everything else goes back.
 */
public final class DeliverMenu extends Menu {

    private static final Sound PAID = Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER, 1f, 1f);

    private final Order order;
    private final Material material;
    private boolean processed;

    public DeliverMenu(TuskOrders plugin, Player viewer, Order order) {
        super(plugin, viewer, 4, plugin.messages().render("gui.deliver.title", Outcome.vars(
                "item_key", order.item(),
                "remaining", String.valueOf(order.remaining()),
                "price", plugin.service().money().format(order.priceEach()))));
        this.order = order;
        this.material = Material.matchMaterial(order.item());
    }

    @Override
    protected void render() {
        // Starts empty; the player fills it
    }

    @Override
    boolean freeSlots() {
        return true;
    }

    @Override
    void closed(Player player) {
        process(player);
    }

    /** Hands in the contents. Safe to call more than once; only the first call does anything. */
    public void process(Player player) {
        if (processed) {
            return;
        }
        processed = true;
        Inventory inventory = getInventory();
        List<ItemStack> matching = new ArrayList<>();
        List<ItemStack> leftovers = new ArrayList<>();
        int offered = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (material != null && ItemRules.isPlain(stack, material)) {
                matching.add(stack);
                offered += stack.getAmount();
            } else {
                leftovers.add(stack);
            }
        }
        inventory.clear();
        if (offered == 0 && leftovers.isEmpty()) {
            return;
        }

        OrderService.Delivery delivery = plugin.service().deliver(order.id(), player.getUniqueId(), offered);
        int toKeep = delivery.accepted();
        for (ItemStack stack : matching) {
            int take = Math.min(toKeep, stack.getAmount());
            toKeep -= take;
            if (take < stack.getAmount()) {
                leftovers.add(stack.asQuantity(stack.getAmount() - take));
            }
        }
        giveBack(player, leftovers);

        if (offered > 0) {
            plugin.messages().send(player, delivery.outcome());
        }
        int rejected = leftovers.stream().filter(s -> !ItemRules.isPlain(s, material)).mapToInt(ItemStack::getAmount).sum();
        if (rejected > 0) {
            plugin.messages().send(player, "deliver.rejected", "count", String.valueOf(rejected));
        }
        if (delivery.accepted() > 0) {
            player.playSound(PAID);
            notifyOwner(player, delivery);
        }
    }

    private void notifyOwner(Player deliverer, OrderService.Delivery delivery) {
        Player owner = Bukkit.getPlayer(order.owner());
        if (owner == null) {
            return;
        }
        boolean completed = "0".equals(delivery.outcome().vars().get("remaining"));
        plugin.messages().send(owner, completed ? "notify.completed" : "notify.delivered", Outcome.vars(
                "player", deliverer.getName(),
                "accepted", String.valueOf(delivery.accepted()),
                "amount", String.valueOf(order.amount()),
                "item_key", order.item(),
                "id", String.valueOf(order.id())));
    }

    private static void giveBack(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()
                .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
    }
}
