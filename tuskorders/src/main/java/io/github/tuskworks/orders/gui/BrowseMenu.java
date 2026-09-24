package io.github.tuskworks.orders.gui;

import io.github.tuskworks.orders.TuskOrders;
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

/** The public order board: every open order, newest money first by default. */
public final class BrowseMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private OrderSort sort;
    private String filter;
    private int page;

    public BrowseMenu(TuskOrders plugin, Player viewer, OrderSort sort, String filter) {
        super(plugin, viewer, 6, plugin.messages().render("gui.browse.title"));
        this.sort = sort;
        this.filter = filter;
    }

    @Override
    protected void render() {
        clear();
        List<Order> orders = plugin.service().active(sort, filter);
        int pages = Math.max(1, (orders.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.min(page, pages - 1);

        List<Order> shown = orders.subList(page * PAGE_SIZE, Math.min(orders.size(), (page + 1) * PAGE_SIZE));
        for (int i = 0; i < shown.size(); i++) {
            Order order = shown.get(i);
            set(i, orderIcon(order), (player, click) -> deliver(order.id()));
        }
        if (orders.isEmpty()) {
            String key = filter.isEmpty() ? "gui.browse.empty" : "gui.browse.no-results";
            set(22, Icons.icon(Material.BARRIER, plugin.messages().item(key, Outcome.vars("filter", filter))));
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
        set(47, sortButton(), (p, c) -> {
            sort = sort.next();
            page = 0;
            render();
        });
        set(48, searchButton(), this::search);
        int uncollected = plugin.service().uncollectedCount(viewer.getUniqueId());
        set(49, Icons.icon(Material.CHEST, plugin.messages().item("gui.browse.mine.name", Map.of()),
                        plugin.messages().lore("gui.browse.mine.lore", Outcome.vars(
                                "active", String.valueOf(plugin.service().activeCount(viewer.getUniqueId())),
                                "uncollected", String.valueOf(uncollected)))),
                (p, c) -> switchTo(new MyOrdersMenu(plugin, viewer)));
        if (viewer.hasPermission("tuskorders.create")) {
            set(50, Icons.icon(Material.WRITABLE_BOOK, plugin.messages().item("gui.browse.create.name", Map.of()),
                            plugin.messages().lore("gui.browse.create.lore", Map.of())),
                    (p, c) -> {
                        closeLater();
                        plugin.createFlow().start(p);
                    });
        }
    }

    private Component sortName(OrderSort s) {
        return plugin.messages().render("gui.sort." + s.name().toLowerCase(Locale.ROOT));
    }

    private org.bukkit.inventory.ItemStack sortButton() {
        List<Component> lore = new ArrayList<>();
        for (OrderSort s : OrderSort.values()) {
            String key = s == sort ? "gui.browse.sort.selected" : "gui.browse.sort.option";
            lore.addAll(plugin.messages().lore(key, Map.of(), Placeholder.component("sort", sortName(s))));
        }
        lore.addAll(plugin.messages().lore("gui.browse.sort.hint", Map.of()));
        return Icons.icon(Material.HOPPER, plugin.messages().item("gui.browse.sort.name", Map.of()), lore);
    }

    private org.bukkit.inventory.ItemStack searchButton() {
        String loreKey = filter.isEmpty() ? "gui.browse.search.lore" : "gui.browse.search.lore-active";
        return Icons.icon(Material.OAK_SIGN, plugin.messages().item("gui.browse.search.name", Map.of()),
                plugin.messages().lore(loreKey, Outcome.vars("filter", filter)));
    }

    private void search(Player player, ClickType click) {
        if (click.isRightClick()) {
            filter = "";
            page = 0;
            render();
            return;
        }
        closeLater();
        plugin.messages().send(player, "search.prompt");
        OrderSort current = sort;
        plugin.prompts().ask(player, input -> new BrowseMenu(plugin, player, current, input).open());
    }

    private org.bukkit.inventory.ItemStack orderIcon(Order order) {
        Money money = plugin.service().money();
        boolean own = order.owner().equals(viewer.getUniqueId());
        Map<String, String> vars = Outcome.vars(
                "id", String.valueOf(order.id()),
                "owner", order.ownerName(),
                "price", money.format(order.priceEach()),
                "delivered", String.valueOf(order.delivered()),
                "amount", String.valueOf(order.amount()),
                "remaining", String.valueOf(order.remaining()),
                "payout", money.compact(order.escrow()),
                "expires", order.expiresAt() == 0 ? "-" : Icons.timeLeft(
                        Duration.ofMillis(order.expiresAt() - System.currentTimeMillis())));
        List<Component> lore = new ArrayList<>(plugin.messages().lore("gui.browse.order.lore", vars,
                Placeholder.parsed("bar", Icons.bar(order.delivered(), order.amount()))));
        lore.addAll(plugin.messages().lore(own ? "gui.browse.order.own" : "gui.browse.order.deliver", vars));
        Material material = Material.matchMaterial(order.item());
        return Icons.icon(material == null ? Material.BARRIER : material, null, lore);
    }

    private void deliver(int orderId) {
        Order order = plugin.service().get(orderId).filter(Order::active).orElse(null);
        if (order == null) {
            plugin.messages().send(viewer, "deliver.unavailable");
            render();
            return;
        }
        if (order.owner().equals(viewer.getUniqueId())) {
            plugin.messages().send(viewer, "deliver.own-order");
            return;
        }
        switchTo(new DeliverMenu(plugin, viewer, order));
    }
}
