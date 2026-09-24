package io.github.tuskworks.orders.command;

import io.github.tuskworks.orders.TuskOrders;
import io.github.tuskworks.orders.gui.BrowseMenu;
import io.github.tuskworks.orders.gui.MyOrdersMenu;
import io.github.tuskworks.orders.lang.Messages;
import io.github.tuskworks.orders.money.Money;
import io.github.tuskworks.orders.prompt.CreateFlow;
import io.github.tuskworks.orders.service.OrderSort;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class OrdersCommand implements BasicCommand {

    /** Subcommands with the permission each needs. */
    private static final Map<String, String> SUBCOMMANDS = Map.of(
            "browse", "tuskorders.use",
            "create", "tuskorders.create",
            "mine", "tuskorders.use",
            "search", "tuskorders.use",
            "help", "tuskorders.use",
            "reload", "tuskorders.admin",
            "cancel", "tuskorders.admin");

    private final TuskOrders plugin;

    public OrdersCommand(TuskOrders plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "tuskorders.use";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "browse" : args[0].toLowerCase(Locale.ROOT);
        String permission = SUBCOMMANDS.get(sub);
        if (permission == null) {
            messages().send(sender, "error.unknown-command", "command", args[0]);
            return;
        }
        if (!sender.hasPermission(permission)) {
            messages().send(sender, "error.no-permission");
            return;
        }
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        switch (sub) {
            case "help" -> messages().send(sender, sender.hasPermission("tuskorders.admin") ? "help.admin" : "help.player");
            case "reload" -> {
                plugin.reload();
                messages().send(sender, "reload.success");
            }
            case "cancel" -> adminCancel(sender, rest);
            default -> {
                if (!(sender instanceof Player player)) {
                    messages().send(sender, "error.players-only");
                    return;
                }
                if (!plugin.bank().available()) {
                    messages().send(player, "error.no-economy");
                    return;
                }
                switch (sub) {
                    case "create" -> create(player, rest);
                    case "mine" -> new MyOrdersMenu(plugin, player).open();
                    case "search" -> new BrowseMenu(plugin, player, OrderSort.PRICE, String.join(" ", rest)).open();
                    default -> new BrowseMenu(plugin, player, OrderSort.PRICE, "").open();
                }
            }
        }
    }

    private void create(Player player, String[] args) {
        if (args.length == 0) {
            plugin.createFlow().start(player);
            return;
        }
        if (args.length != 3) {
            messages().send(player, "create.usage");
            return;
        }
        Optional<Material> material = plugin.createFlow().resolveItem(player, args[0]);
        if (material.isEmpty()) {
            return;
        }
        OptionalInt amount = CreateFlow.parseAmount(args[1]);
        if (amount.isEmpty()) {
            messages().send(player, "error.invalid-amount", "input", args[1]);
            return;
        }
        OptionalLong price = Money.parse(args[2]);
        if (price.isEmpty()) {
            messages().send(player, "error.invalid-price", "input", args[2]);
            return;
        }
        plugin.createFlow().confirm(player, material.get(), amount.getAsInt(), price.getAsLong());
    }

    private void adminCancel(CommandSender sender, String[] args) {
        if (args.length != 1) {
            messages().send(sender, "cancel.usage");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[0].replace("#", ""));
        } catch (NumberFormatException e) {
            messages().send(sender, "cancel.not-found", "id", args[0]);
            return;
        }
        messages().send(sender, plugin.service().cancel(id, null, true));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.entrySet().stream()
                    .filter(e -> sender.hasPermission(e.getValue()) && e.getKey().startsWith(prefix))
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("create") && sender.hasPermission("tuskorders.create")) {
            return switch (args.length) {
                case 2 -> plugin.itemRules().suggestions(args[1]);
                case 3 -> List.of("64", "576", "1728");
                case 4 -> List.of("10", "1k", "2.5k");
                default -> List.of();
            };
        }
        if (sub.equals("search") && args.length == 2) {
            return plugin.itemRules().suggestions(args[1]);
        }
        return List.of();
    }

    private Messages messages() {
        return plugin.messages();
    }
}
