package io.github.tuskworks.orders.gui;

import io.github.tuskworks.orders.TuskOrders;
import io.github.tuskworks.orders.item.ItemRules;
import io.github.tuskworks.orders.model.Order;
import io.github.tuskworks.orders.money.Money;
import io.github.tuskworks.orders.service.OrderSort;
import io.github.tuskworks.orders.service.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/** The viewer's own orders: collect delivered items or cancel open orders. */
public final class MyOrdersMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private int page;

    public MyOrdersMenu(TuskOrders plugin, Player viewer) {
        super(plugin, viewer, 6, plugin.messages().render("gui.mine.title"));
    }

    @Override
    protected void render() {
        clear();
        List<Order> orders = plugin.service().byOwner(viewer.getUniqueId());
        int pages = Math.max(1, (orders.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);
        List<Order> shown = orders.subList(page * PAGE_SIZE, Math.min(orders.size(), (page + 1) * PAGE_SIZE));
        for (int i = 0; i < shown.size(); i++) {
            Order order = shown.get(i);
            set(i, icon(order), (player, click) -> clicked(order.id(), click));
        }
        if (orders.isEmpty()) {
            set(22, Icons.icon(Material.BARRIER, plugin.messages().item("gui.mine.empty", Map.of())));
        }

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Icons.filler());
        }
        Map<String, String> pageVars = Outcome.vars("page", String.valueOf(page + 1), "pages", String.valueOf(pages));
        if (page > 0) {
            set(45, Icons.icon(Material.ARROW, plugin.messages().item("gui.previous-page", pageVars)), (p, c) -> {
                page--;
                render();
            });
        }
        if (page < pages - 1) {
            set(53, Icons.icon(Material.ARROW, plugin.messages().item("gui.next-page", pageVars)), (p, c) -> {
                page++;
                render();
            });
        }
        set(49, Icons.icon(Material.OAK_DOOR, plugin.messages().item("gui.mine.back", Map.of())),
                (p, c) -> switchTo(new BrowseMenu(plugin, viewer, OrderSort.PRICE, "")));
        if (orders.stream().anyMatch(o -> o.uncollected() > 0)) {
            set(50, Icons.icon(Material.HOPPER, plugin.messages().item("gui.mine.collect-all", Map.of())),
                    (p, c) -> collectAll());
        }
    }

    private ItemStack icon(Order order) {
        Money money = plugin.service().money();
        Map<String, String> vars = Outcome.vars(
                "id", String.valueOf(order.id()),
                "price", money.format(order.priceEach()),
                "delivered", String.valueOf(order.delivered()),
                "amount", String.valueOf(order.amount()),
                "uncollected", String.valueOf(order.uncollected()),
                "escrow", money.format(order.escrow()),
                "expires", order.expiresAt() == 0 ? "-" : Icons.timeLeft(
                        Duration.ofMillis(order.expiresAt() - System.currentTimeMillis())));
        Component status = plugin.messages().render("gui.status." + order.status().name().toLowerCase(Locale.ROOT));
        List<Component> lore = new ArrayList<>(plugin.messages().lore("gui.mine.order.lore", vars,
                Placeholder.component("status", status),
                Placeholder.parsed("bar", Icons.bar(order.delivered(), order.amount()))));
        if (order.uncollected() > 0) {
            lore.addAll(plugin.messages().lore("gui.mine.order.collect", vars));
        }
        if (order.active()) {
            lore.addAll(plugin.messages().lore("gui.mine.order.cancel", vars));
        }
        Material material = Material.matchMaterial(order.item());
        return Icons.icon(material == null ? Material.BARRIER : material, null, lore);
    }

    private void clicked(int orderId, ClickType click) {
        Order order = plugin.service().get(orderId).orElse(null);
        if (order == null) {
            render();
            return;
        }
        if (click == ClickType.SHIFT_RIGHT && order.active()) {
            confirmCancel(order);
            return;
        }
        if (click.isLeftClick()) {
            collect(order);
            render();
        }
    }

    private void confirmCancel(Order order) {
        Map<String, String> vars = Outcome.vars(
                "id", String.valueOf(order.id()),
                "refund", plugin.service().money().format(order.escrow()),
                "item_key", order.item());
        Material material = Material.matchMaterial(order.item());
        ItemStack summary = Icons.icon(material == null ? Material.BARRIER : material,
                plugin.messages().item("gui.cancel.summary.name", vars),
                plugin.messages().lore("gui.cancel.summary.lore", vars));
        switchTo(new ConfirmMenu(plugin, viewer, plugin.messages().render("gui.cancel.title"), summary,
                player -> {
                    plugin.messages().send(player, plugin.service().cancel(order.id(), player.getUniqueId(), false));
                    switchTo(new MyOrdersMenu(plugin, viewer));
                },
                player -> switchTo(new MyOrdersMenu(plugin, viewer))));
    }

    /** @return false if the player's inventory ran out of space */
    private boolean collect(Order order) {
        Material material = Material.matchMaterial(order.item());
        if (material == null || order.uncollected() == 0) {
            return true;
        }
        int space = space(material);
        int taken = plugin.service().collect(order.id(), viewer.getUniqueId(), space);
        if (taken > 0) {
            give(material, taken);
            plugin.messages().send(viewer, "collect.success", Outcome.vars(
                    "count", String.valueOf(taken), "item_key", order.item()));
        }
        if (taken < order.uncollected()) {
            plugin.messages().send(viewer, "collect.inventory-full");
            return false;
        }
        return true;
    }

    private void collectAll() {
        for (Order order : plugin.service().byOwner(viewer.getUniqueId())) {
            if (order.uncollected() > 0 && !collect(order)) {
                break;
            }
        }
        render();
    }

    /** How many plain items of this type fit into the player's main inventory. */
    private int space(Material material) {
        int max = material.getMaxStackSize();
        int space = 0;
        for (ItemStack stack : viewer.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                space += max;
            } else if (ItemRules.isPlain(stack, material)) {
                space += Math.max(0, max - stack.getAmount());
            }
        }
        return space;
    }

    private void give(Material material, int count) {
        int max = material.getMaxStackSize();
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = count; left > 0; left -= max) {
            stacks.add(new ItemStack(material, Math.min(max, left)));
        }
        viewer.getInventory().addItem(stacks.toArray(ItemStack[]::new)).values()
                .forEach(overflow -> viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow));
    }
}
