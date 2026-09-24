package io.github.tuskworks.orders.prompt;

import io.github.tuskworks.orders.TuskOrders;
import io.github.tuskworks.orders.gui.ConfirmMenu;
import io.github.tuskworks.orders.gui.Icons;
import io.github.tuskworks.orders.item.ItemRules;
import io.github.tuskworks.orders.money.Money;
import io.github.tuskworks.orders.service.OrderSettings;
import io.github.tuskworks.orders.service.Outcome;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Placing an order: item, amount and price are asked in chat (or given on the command line),
 * then a confirm screen shows exactly what will be charged.
 */
public final class CreateFlow {

    private final TuskOrders plugin;

    public CreateFlow(TuskOrders plugin) {
        this.plugin = plugin;
    }

    public void start(Player player) {
        plugin.messages().send(player, "create.prompt-item");
        plugin.prompts().ask(player, input -> item(player, input));
    }

    private void item(Player player, String input) {
        Optional<Material> material = resolveItem(player, input);
        if (material.isEmpty()) {
            plugin.prompts().ask(player, retry -> item(player, retry));
            return;
        }
        plugin.messages().send(player, "create.prompt-amount", Outcome.vars(
                "item_key", ItemRules.key(material.get()),
                "max", String.valueOf(plugin.service().settings().maxAmount())));
        plugin.prompts().ask(player, answer -> amount(player, material.get(), answer));
    }

    private void amount(Player player, Material material, String input) {
        OptionalInt amount = parseAmount(input);
        if (amount.isEmpty()) {
            plugin.messages().send(player, "error.invalid-amount", "input", input);
            plugin.prompts().ask(player, retry -> amount(player, material, retry));
            return;
        }
        plugin.messages().send(player, "create.prompt-price", Outcome.vars(
                "item_key", ItemRules.key(material), "amount", String.valueOf(amount.getAsInt())));
        plugin.prompts().ask(player, answer -> price(player, material, amount.getAsInt(), answer));
    }

    private void price(Player player, Material material, int amount, String input) {
        OptionalLong price = Money.parse(input);
        if (price.isEmpty()) {
            plugin.messages().send(player, "error.invalid-price", "input", input);
            plugin.prompts().ask(player, retry -> price(player, material, amount, retry));
            return;
        }
        confirm(player, material, amount, price.getAsLong());
    }

    /** Resolves typed item input, telling the player what went wrong if it can't be ordered. */
    public Optional<Material> resolveItem(Player player, String input) {
        Optional<Material> material = input.equalsIgnoreCase("hand")
                ? Optional.of(player.getInventory().getItemInMainHand().getType()).filter(m -> !m.isAir())
                : ItemRules.parse(input);
        if (material.isEmpty()) {
            plugin.messages().send(player, "error.unknown-item", "input", input);
            return Optional.empty();
        }
        if (!plugin.itemRules().allowed(material.get())) {
            plugin.messages().send(player, "error.blocked-item", "item_key", ItemRules.key(material.get()));
            return Optional.empty();
        }
        return material;
    }

    public static OptionalInt parseAmount(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        int multiplier = 1;
        if (s.endsWith("k")) {
            multiplier = 1000;
            s = s.substring(0, s.length() - 1);
        }
        try {
            long value = Long.parseLong(s) * multiplier;
            return value > 0 && value <= Integer.MAX_VALUE ? OptionalInt.of((int) value) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** Shows what the order will cost and places it on confirmation. */
    public void confirm(Player player, Material material, int amount, long priceEach) {
        Optional<Outcome> invalid = plugin.service().validate(amount, priceEach);
        if (invalid.isPresent()) {
            plugin.messages().send(player, invalid.get());
            return;
        }
        OrderSettings settings = plugin.service().settings();
        Money money = plugin.service().money();
        long total = priceEach * amount;
        long fee = Money.percentOf(total, settings.creationFeePercent());
        String itemKey = ItemRules.key(material);
        Map<String, String> vars = Outcome.vars(
                "item_key", itemKey,
                "amount", String.valueOf(amount),
                "price", money.format(priceEach),
                "total", money.format(total + fee),
                "fee", money.format(fee),
                "expires", settings.expiry().isZero() ? "-" : Icons.timeLeft(settings.expiry()));

        ItemStack summary = new ItemStack(material);
        ItemMeta meta = summary.getItemMeta();
        meta.displayName(plugin.messages().item("gui.create.summary.name", vars));
        meta.lore(plugin.messages().lore(fee > 0 ? "gui.create.summary.lore-fee" : "gui.create.summary.lore", vars));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        summary.setItemMeta(meta);

        new ConfirmMenu(plugin, player, plugin.messages().render("gui.create.title"), summary,
                p -> plugin.messages().send(p, plugin.service().create(p.getUniqueId(), p.getName(), itemKey,
                        amount, priceEach, plugin.activeOrderLimit(p))),
                p -> plugin.messages().send(p, "prompt.cancelled")).open();
    }
}
