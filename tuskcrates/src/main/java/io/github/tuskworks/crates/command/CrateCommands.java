package io.github.tuskworks.crates.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.tuskworks.crates.TuskCratesPlugin;
import io.github.tuskworks.crates.crate.Crate;
import io.github.tuskworks.crates.gui.CratesMenu;
import io.github.tuskworks.crates.gui.PreviewMenu;
import io.github.tuskworks.crates.key.KeyType;
import io.github.tuskworks.crates.location.BlockPos;
import io.github.tuskworks.crates.open.OpenService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class CrateCommands {

    private static final String ADMIN = "tuskcrates.admin";

    private final TuskCratesPlugin plugin;

    private CrateCommands(TuskCratesPlugin plugin) {
        this.plugin = plugin;
    }

    public static void register(TuskCratesPlugin plugin) {
        CrateCommands commands = new CrateCommands(plugin);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(commands.admin(), "Manage TuskCrates", List.of("tc"));
            event.registrar().register(commands.menu(), "Open the crates menu");
        });
    }

    private LiteralCommandNode<CommandSourceStack> menu() {
        return Commands.literal("crates")
                .requires(src -> src.getSender().hasPermission("tuskcrates.menu"))
                .executes(ctx -> {
                    Player player = requirePlayer(ctx);
                    if (player != null) {
                        new CratesMenu(plugin, player).open();
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> admin() {
        return Commands.literal("tuskcrates")
                .requires(src -> src.getSender().hasPermission(ADMIN))
                .then(give())
                .then(keyAll())
                .then(take())
                .then(keys())
                // set/remove target the block the player looks at, or explicit coordinates (console, command blocks)
                .then(Commands.literal("set").then(crateArg()
                        .executes(ctx -> setCrate(ctx, lookedAt(ctx)))
                        .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                                .executes(ctx -> setCrate(ctx, position(ctx))))))
                .then(Commands.literal("remove")
                        .executes(ctx -> removeCrate(ctx, lookedAt(ctx)))
                        .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                                .executes(ctx -> removeCrate(ctx, position(ctx)))))
                .then(Commands.literal("additem").then(crateArg()
                        .then(Commands.argument("weight", DoubleArgumentType.doubleArg(0.0001))
                                .executes(this::addItem))))
                .then(Commands.literal("open").then(crateArg().executes(this::forceOpen)))
                .then(Commands.literal("preview").then(crateArg().executes(this::preview)))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("reload").executes(this::reload))
                .build();
    }

    // /tc give <targets> <crate> [amount] [virtual|physical]
    private ArgumentBuilder<CommandSourceStack, ?> give() {
        return Commands.literal("give").then(Commands.argument("targets", ArgumentTypes.players())
                .then(crateArg()
                        .executes(ctx -> give(ctx, 1, null))
                        .then(amountArg()
                                .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount"), null))
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, b) -> suggest(b, List.of("virtual", "physical")))
                                        .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount"),
                                                StringArgumentType.getString(ctx, "type")))))));
    }

    private int give(CommandContext<CommandSourceStack> ctx, int amount, String typeName) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Optional<Crate> crate = crate(ctx);
        if (crate.isEmpty()) {
            return 0;
        }
        KeyType type = KeyType.parse(typeName, plugin.settings().defaultKeyType());
        List<Player> targets = ctx.getArgument("targets", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        for (Player target : targets) {
            giveKeys(target, crate.get(), amount, type);
        }
        plugin.messages().send(sender, "admin.keys-given", tags(crate.get(),
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("count", String.valueOf(targets.size()))));
        return targets.size();
    }

    private void giveKeys(Player target, Crate crate, int amount, KeyType type) {
        if (type == KeyType.VIRTUAL) {
            plugin.keyStorage().add(target.getUniqueId(), crate.id(), amount);
        } else {
            target.getScheduler().run(plugin, task -> {
                int left = amount;
                while (left > 0) {
                    ItemStack key = plugin.keys().createKey(crate, left);
                    left -= key.getAmount();
                    target.getInventory().addItem(key).values()
                            .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
                }
            }, null);
        }
        plugin.messages().send(target, "key-received", tags(crate, Placeholder.unparsed("amount", String.valueOf(amount))));
    }

    // /tc keyall <crate> [amount]: virtual keys for everyone online
    private ArgumentBuilder<CommandSourceStack, ?> keyAll() {
        return Commands.literal("keyall").then(crateArg()
                .executes(ctx -> keyAll(ctx, 1))
                .then(amountArg().executes(ctx -> keyAll(ctx, IntegerArgumentType.getInteger(ctx, "amount")))));
    }

    private int keyAll(CommandContext<CommandSourceStack> ctx, int amount) {
        Optional<Crate> crate = crate(ctx);
        if (crate.isEmpty()) {
            return 0;
        }
        var online = List.copyOf(plugin.getServer().getOnlinePlayers());
        online.forEach(player -> plugin.keyStorage().add(player.getUniqueId(), crate.get().id(), amount));
        TagResolver tags = tags(crate.get(), Placeholder.unparsed("amount", String.valueOf(amount)));
        plugin.getServer().forEachAudience(audience -> plugin.messages().send(audience, "keyall-broadcast", tags));
        return online.size();
    }

    // /tc take <player> <crate> [amount]
    private ArgumentBuilder<CommandSourceStack, ?> take() {
        return Commands.literal("take").then(Commands.argument("player", ArgumentTypes.player())
                .then(crateArg()
                        .executes(ctx -> take(ctx, 1))
                        .then(amountArg().executes(ctx -> take(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))));
    }

    private int take(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
        Optional<Crate> crate = crate(ctx);
        if (crate.isEmpty()) {
            return 0;
        }
        Player target = singlePlayer(ctx, "player");
        int removed = plugin.keyStorage().remove(target.getUniqueId(), crate.get().id(), amount);
        plugin.messages().send(ctx.getSource().getSender(), "admin.keys-taken", tags(crate.get(),
                Placeholder.unparsed("amount", String.valueOf(removed)),
                Placeholder.unparsed("player", target.getName())));
        return Command.SINGLE_SUCCESS;
    }

    // /tc keys [player]
    private ArgumentBuilder<CommandSourceStack, ?> keys() {
        return Commands.literal("keys")
                .executes(ctx -> {
                    Player player = requirePlayer(ctx);
                    return player == null ? 0 : showKeys(ctx.getSource().getSender(), player);
                })
                .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(ctx -> showKeys(ctx.getSource().getSender(), singlePlayer(ctx, "player"))));
    }

    private int showKeys(CommandSender sender, Player target) {
        plugin.messages().send(sender, "admin.keys-view-header", Placeholder.unparsed("player", target.getName()));
        Map<String, Integer> balance = plugin.keyStorage().all(target.getUniqueId());
        for (Crate crate : plugin.crates().all()) {
            plugin.messages().send(sender, "admin.keys-view-line", tags(crate,
                    Placeholder.unparsed("amount", String.valueOf(balance.getOrDefault(crate.id(), 0)))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int setCrate(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        Optional<Crate> crate = crate(ctx);
        if (pos == null || crate.isEmpty()) {
            return 0;
        }
        plugin.locations().set(pos, crate.get().id());
        plugin.saveLocationsAsync();
        plugin.holograms().refresh(pos);
        plugin.messages().send(ctx.getSource().getSender(), "admin.crate-set", OpenService.crateTag(crate.get()));
        return Command.SINGLE_SUCCESS;
    }

    private int removeCrate(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        if (pos == null) {
            return 0;
        }
        CommandSender sender = ctx.getSource().getSender();
        Optional<Crate> crate = plugin.locations().crateAt(pos).flatMap(plugin.crates()::get);
        if (!plugin.locations().remove(pos)) {
            plugin.messages().send(sender, "admin.not-a-crate");
            return 0;
        }
        plugin.saveLocationsAsync();
        plugin.holograms().refresh(pos);
        crate.ifPresent(c -> plugin.messages().send(sender, "admin.crate-removed", OpenService.crateTag(c)));
        return Command.SINGLE_SUCCESS;
    }

    /** The block the executing player looks at, or null after telling the sender why not. */
    private BlockPos lookedAt(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return null;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null || block.getType().isAir()) {
            plugin.messages().send(player, "admin.not-looking-at-block");
            return null;
        }
        return BlockPos.of(block);
    }

    private static BlockPos position(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        BlockPosition pos = ctx.getArgument("pos", BlockPositionResolver.class).resolve(ctx.getSource());
        UUID world = ctx.getSource().getLocation().getWorld().getUID();
        return new BlockPos(world, pos.blockX(), pos.blockY(), pos.blockZ());
    }

    private int addItem(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        Optional<Crate> crate = crate(ctx);
        if (player == null || crate.isEmpty()) {
            return 0;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            plugin.messages().send(player, "admin.hold-an-item");
            return 0;
        }
        double weight = DoubleArgumentType.getDouble(ctx, "weight");
        try {
            Crate updated = plugin.crates().addItemReward(crate.get(), hand.clone(), weight);
            plugin.messages().send(player, "admin.item-added", tags(updated,
                    Placeholder.unparsed("weight", String.valueOf(weight))));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + crate.get().file().getName() + ": " + e.getMessage());
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int forceOpen(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        Optional<Crate> crate = crate(ctx);
        if (player != null && crate.isPresent()) {
            plugin.openService().forceOpen(player, crate.get());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int preview(CommandContext<CommandSourceStack> ctx) {
        Player player = requirePlayer(ctx);
        Optional<Crate> crate = crate(ctx);
        if (player != null && crate.isPresent()) {
            new PreviewMenu(plugin, crate.get()).open(player);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        plugin.messages().send(sender, "admin.list-header");
        for (Crate crate : plugin.crates().all()) {
            plugin.messages().send(sender, "admin.list-line", tags(crate,
                    Placeholder.unparsed("rewards", String.valueOf(crate.rewards().size())),
                    Placeholder.unparsed("animation", crate.animation().name().toLowerCase(Locale.ROOT))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        int count = plugin.reload();
        plugin.messages().send(ctx.getSource().getSender(), "admin.reloaded",
                Placeholder.unparsed("count", String.valueOf(count)));
        return Command.SINGLE_SUCCESS;
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> crateArg() {
        return Commands.argument("crate", StringArgumentType.word())
                .suggests((ctx, builder) -> suggest(builder, plugin.crates().ids()));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> amountArg() {
        return Commands.argument("amount", IntegerArgumentType.integer(1, 100_000));
    }

    private Optional<Crate> crate(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "crate");
        Optional<Crate> crate = plugin.crates().get(id);
        if (crate.isEmpty()) {
            plugin.messages().send(ctx.getSource().getSender(), "admin.unknown-crate", Placeholder.unparsed("id", id));
        }
        return crate;
    }

    private Player requirePlayer(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getExecutor() instanceof Player player) {
            return player;
        }
        plugin.messages().send(ctx.getSource().getSender(), "admin.players-only");
        return null;
    }

    private static Player singlePlayer(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return ctx.getArgument(name, PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    }

    private static TagResolver tags(Crate crate, TagResolver... extra) {
        return TagResolver.resolver(OpenService.crateTag(crate),
                Placeholder.unparsed("id", crate.id()), TagResolver.resolver(extra));
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, Iterable<String> options) {
        String typed = builder.getRemainingLowerCase();
        for (String option : options) {
            if (option.startsWith(typed)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }
}
