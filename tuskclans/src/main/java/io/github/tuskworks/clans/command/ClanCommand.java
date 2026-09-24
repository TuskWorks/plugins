package io.github.tuskworks.clans.command;

import io.github.tuskworks.clans.ClanChat;
import io.github.tuskworks.clans.TuskClans;
import io.github.tuskworks.clans.config.PluginConfig;
import io.github.tuskworks.clans.gui.ClanMenu;
import io.github.tuskworks.clans.hook.EconomyHook;
import io.github.tuskworks.clans.lang.Messages;
import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanHome;
import io.github.tuskworks.clans.model.ClanMember;
import io.github.tuskworks.clans.service.ClanService;
import io.github.tuskworks.clans.service.Outcome;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ClanCommand implements BasicCommand {

    private static final int PAGE_SIZE = 10;

    /** Subcommands in help order, with the permission each needs. */
    private static final Map<String, String> SUBCOMMANDS = orderedMap(
            "menu", "tuskclans.use",
            "create", "tuskclans.create",
            "info", "tuskclans.use",
            "list", "tuskclans.use",
            "top", "tuskclans.use",
            "invite", "tuskclans.use",
            "accept", "tuskclans.use",
            "deny", "tuskclans.use",
            "join", "tuskclans.use",
            "leave", "tuskclans.use",
            "kick", "tuskclans.use",
            "promote", "tuskclans.use",
            "demote", "tuskclans.use",
            "transfer", "tuskclans.use",
            "disband", "tuskclans.use",
            "chat", "tuskclans.use",
            "allychat", "tuskclans.use",
            "home", "tuskclans.home",
            "sethome", "tuskclans.use",
            "delhome", "tuskclans.use",
            "ff", "tuskclans.use",
            "open", "tuskclans.use",
            "ally", "tuskclans.use",
            "unally", "tuskclans.use",
            "admin", "tuskclans.admin");

    private final TuskClans plugin;

    public ClanCommand(TuskClans plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "tuskclans.use";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0 && sender instanceof Player player) {
            ClanMenu.open(plugin, player);
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String permission = SUBCOMMANDS.get(sub);
        if (permission == null) {
            messages().send(sender, "error.unknown-command", "command", args[0]);
            return;
        }
        if (!sender.hasPermission(permission)) {
            messages().send(sender, "error.no-permission");
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (sub.equals("admin")) {
            admin(sender, rest);
            return;
        }
        if (sub.equals("info") || sub.equals("list") || sub.equals("top")) {
            switch (sub) {
                case "info" -> info(sender, rest);
                case "list" -> list(sender, rest);
                default -> top(sender);
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.players-only");
            return;
        }
        switch (sub) {
            case "menu" -> ClanMenu.open(plugin, player);
            case "create" -> create(player, rest);
            case "invite" -> invite(player, rest);
            case "accept" -> reply(player, service().accept(player.getUniqueId(), player.getName(), optionalArg(rest)));
            case "deny" -> reply(player, service().deny(player.getUniqueId(), optionalArg(rest)));
            case "join" -> requireArg(player, rest, "join",
                    tag -> reply(player, service().join(player.getUniqueId(), player.getName(), tag)));
            case "leave" -> {
                Outcome outcome = service().leave(player.getUniqueId());
                if (outcome.success()) {
                    plugin.chat().reset(player.getUniqueId());
                }
                reply(player, outcome);
            }
            case "kick" -> requireArg(player, rest, "kick", name -> reply(player, service().kick(player.getUniqueId(), name)));
            case "promote" -> requireArg(player, rest, "promote",
                    name -> reply(player, service().promote(player.getUniqueId(), name)));
            case "demote" -> requireArg(player, rest, "demote",
                    name -> reply(player, service().demote(player.getUniqueId(), name)));
            case "transfer" -> transfer(player, rest);
            case "disband" -> disband(player, rest);
            case "chat" -> channel(player, rest, false);
            case "allychat" -> channel(player, rest, true);
            case "home" -> home(player);
            case "sethome" -> setHome(player);
            case "delhome" -> reply(player, service().deleteHome(player.getUniqueId()));
            case "ff" -> reply(player, service().toggleFriendlyFire(player.getUniqueId()));
            case "open" -> reply(player, service().toggleOpen(player.getUniqueId()));
            case "ally" -> requireArg(player, rest, "ally", tag -> reply(player, service().ally(player.getUniqueId(), tag)));
            case "unally" -> requireArg(player, rest, "unally",
                    tag -> reply(player, service().unally(player.getUniqueId(), tag)));
            default -> help(sender);
        }
    }

    // ---- subcommands ---------------------------------------------------------------------

    private void create(Player player, String[] args) {
        if (args.length < 2) {
            usage(player, "create");
            return;
        }
        String tag = args[0];
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        double cost = config().createCost();
        EconomyHook economy = plugin.economy();
        boolean charge = cost > 0 && economy != null && !player.hasPermission("tuskclans.bypass.cost");
        if (charge && !economy.has(player, cost)) {
            messages().send(player, "error.insufficient-funds", "cost", economy.format(cost));
            return;
        }
        Outcome outcome = service().create(player.getUniqueId(), player.getName(), tag, name);
        if (outcome.success() && charge) {
            economy.withdraw(player, cost);
            messages().send(player, "create.charged", "cost", economy.format(cost));
        }
        reply(player, outcome);
    }

    private void invite(Player player, String[] args) {
        if (args.length < 1) {
            usage(player, "invite");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !player.canSee(target)) {
            messages().send(player, "error.player-not-found", "player", args[0]);
            return;
        }
        reply(player, service().invite(player.getUniqueId(), target.getUniqueId(), target.getName()));
    }

    private void transfer(Player player, String[] args) {
        if (args.length < 1) {
            usage(player, "transfer");
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            messages().send(player, "transfer.confirm", "player", args[0]);
            return;
        }
        reply(player, service().transfer(player.getUniqueId(), args[0]));
    }

    private void disband(Player player, String[] args) {
        Optional<Clan> clan = service().clanOf(player.getUniqueId());
        if (clan.isEmpty()) {
            messages().send(player, "error.not-in-clan");
            return;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("confirm")) {
            messages().send(player, "disband.confirm", "tag", clan.get().tag());
            return;
        }
        reply(player, service().disband(player.getUniqueId()));
    }

    private void channel(Player player, String[] args, boolean ally) {
        if (service().clanOf(player.getUniqueId()).isEmpty()) {
            messages().send(player, "error.not-in-clan");
            return;
        }
        if (args.length > 0) {
            plugin.chat().send(player, Component.text(String.join(" ", args)), ally);
            return;
        }
        ClanChat.Mode mode = plugin.chat().toggle(player.getUniqueId(), ally ? ClanChat.Mode.ALLY : ClanChat.Mode.CLAN);
        messages().send(player, "chat.mode-" + mode.name().toLowerCase(Locale.ROOT));
    }

    private void home(Player player) {
        if (!config().home().enabled()) {
            messages().send(player, "error.home-disabled");
            return;
        }
        Optional<Clan> clan = service().clanOf(player.getUniqueId());
        if (clan.isEmpty()) {
            messages().send(player, "error.not-in-clan");
            return;
        }
        Optional<ClanHome> home = clan.get().home();
        if (home.isEmpty()) {
            messages().send(player, "error.no-home");
            return;
        }
        if (config().isDisabledWorld(player.getWorld().getName()) || config().isDisabledWorld(home.get().world())) {
            messages().send(player, "error.world-disabled");
            return;
        }
        plugin.homes().teleport(player, home.get());
    }

    private void setHome(Player player) {
        if (!config().home().enabled()) {
            messages().send(player, "error.home-disabled");
            return;
        }
        if (config().isDisabledWorld(player.getWorld().getName())) {
            messages().send(player, "error.world-disabled");
            return;
        }
        Location l = player.getLocation();
        reply(player, service().setHome(player.getUniqueId(),
                new ClanHome(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch())));
    }

    private void info(CommandSender sender, String[] args) {
        Optional<Clan> clan;
        if (args.length > 0) {
            clan = service().byTag(args[0]);
            if (clan.isEmpty()) {
                messages().send(sender, "error.clan-not-found", "tag", args[0]);
                return;
            }
        } else if (sender instanceof Player player) {
            clan = service().clanOf(player.getUniqueId());
            if (clan.isEmpty()) {
                messages().send(sender, "error.not-in-clan");
                return;
            }
        } else {
            usage(sender, "info");
            return;
        }
        Clan c = clan.get();
        List<ClanMember> members = c.members().stream()
                .sorted(Comparator.comparing(ClanMember::role, Comparator.reverseOrder())
                        .thenComparing(ClanMember::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        long online = members.stream().filter(m -> Bukkit.getPlayer(m.id()) != null).count();
        String memberList = String.join(", ", members.stream().map(ClanMember::name).toList());
        String allies = String.join(", ", c.allies().stream()
                .map(service()::byId).flatMap(Optional::stream).map(Clan::tag).toList());
        int max = service().settings().maxMembers();
        messages().send(sender, "info", Outcome.vars(
                "tag", c.tag(),
                "name", c.name(),
                "leader", c.leader().name(),
                "members", String.valueOf(c.size()),
                "max", max < 0 ? "∞" : String.valueOf(max),
                "online", String.valueOf(online),
                "member_list", memberList,
                "allies", allies.isEmpty() ? messages().raw("info-none") : allies,
                "kills", String.valueOf(c.kills()),
                "deaths", String.valueOf(c.deaths()),
                "kdr", String.format(Locale.ROOT, "%.2f", c.kdr()),
                "ff", messages().raw(c.friendlyFire() ? "info-on" : "info-off"),
                "open", messages().raw(c.open() ? "info-on" : "info-off")));
    }

    private void list(CommandSender sender, String[] args) {
        List<Clan> clans = service().bySize();
        if (clans.isEmpty()) {
            messages().send(sender, "list.empty");
            return;
        }
        int pages = (clans.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length > 0) {
            try {
                page = Math.clamp(Integer.parseInt(args[0]), 1, pages);
            } catch (NumberFormatException e) {
                usage(sender, "list");
                return;
            }
        }
        messages().send(sender, "list.header", "page", String.valueOf(page), "pages", String.valueOf(pages));
        int from = (page - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, clans.size()); i++) {
            Clan c = clans.get(i);
            messages().send(sender, "list.entry", "rank", String.valueOf(i + 1), "tag", c.tag(), "name", c.name(),
                    "members", String.valueOf(c.size()));
        }
    }

    private void top(CommandSender sender) {
        List<Clan> top = service().top(10);
        if (top.isEmpty()) {
            messages().send(sender, "list.empty");
            return;
        }
        messages().send(sender, "top.header");
        for (int i = 0; i < top.size(); i++) {
            Clan c = top.get(i);
            messages().send(sender, "top.entry", "rank", String.valueOf(i + 1), "tag", c.tag(), "name", c.name(),
                    "kills", String.valueOf(c.kills()), "deaths", String.valueOf(c.deaths()),
                    "kdr", String.format(Locale.ROOT, "%.2f", c.kdr()), "members", String.valueOf(c.size()));
        }
    }

    private void admin(CommandSender sender, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "reload" -> {
                plugin.reload();
                messages().send(sender, "admin.reloaded");
            }
            case "disband" -> {
                if (args.length < 2) {
                    usage(sender, "admin-disband");
                    return;
                }
                messages().send(sender, service().adminDisband(args[1]));
            }
            default -> usage(sender, "admin");
        }
    }

    private void help(CommandSender sender) {
        messages().send(sender, "help.header");
        SUBCOMMANDS.forEach((sub, permission) -> {
            if (sender.hasPermission(permission)) {
                messages().send(sender, "help." + sub);
            }
        });
    }

    // ---- tab completion ------------------------------------------------------------------

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            return filter(SUBCOMMANDS.entrySet().stream()
                    .filter(e -> sender.hasPermission(e.getValue()))
                    .map(Map.Entry::getKey), prefix);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String current = args[args.length - 1];
        Optional<Clan> own = sender instanceof Player p ? service().clanOf(p.getUniqueId()) : Optional.empty();
        if (args.length == 2) {
            return switch (sub) {
                case "invite" -> filter(Bukkit.getOnlinePlayers().stream()
                        .filter(p -> !(sender instanceof Player s) || s.canSee(p))
                        .filter(p -> service().clanOf(p.getUniqueId()).isEmpty())
                        .map(Player::getName), current);
                case "kick", "promote", "demote", "transfer" -> filter(own.stream()
                        .flatMap(c -> c.members().stream())
                        .map(ClanMember::name)
                        .filter(n -> !n.equalsIgnoreCase(sender.getName())), current);
                case "accept", "deny" -> sender instanceof Player p
                        ? filter(service().pendingInvites(p.getUniqueId()).stream().map(Clan::tag), current)
                        : List.of();
                case "info", "join", "ally" -> filter(service().clans().stream()
                        .filter(c -> own.map(o -> !o.id().equals(c.id())).orElse(true) || sub.equals("info"))
                        .map(Clan::tag), current);
                case "unally" -> filter(own.stream()
                        .flatMap(c -> c.allies().stream())
                        .map(service()::byId).flatMap(Optional::stream).map(Clan::tag), current);
                case "disband" -> filter(Stream.of("confirm"), current);
                case "admin" -> sender.hasPermission("tuskclans.admin")
                        ? filter(Stream.of("reload", "disband"), current) : List.of();
                default -> List.of();
            };
        }
        if (args.length == 3) {
            if (sub.equals("transfer")) {
                return filter(Stream.of("confirm"), current);
            }
            if (sub.equals("admin") && args[1].equalsIgnoreCase("disband") && sender.hasPermission("tuskclans.admin")) {
                return filter(service().clans().stream().map(Clan::tag), current);
            }
        }
        return List.of();
    }

    // ---- helpers -------------------------------------------------------------------------

    private void reply(CommandSender sender, Outcome outcome) {
        messages().send(sender, outcome);
    }

    private void usage(CommandSender sender, String sub) {
        messages().send(sender, "error.usage", "usage", messages().raw("usage." + sub));
    }

    private void requireArg(Player player, String[] args, String sub, Consumer<String> action) {
        if (args.length < 1) {
            usage(player, sub);
            return;
        }
        action.accept(args[0]);
    }

    private static String optionalArg(String[] args) {
        return args.length > 0 ? args[0] : null;
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        Predicate<String> matches = s -> s.toLowerCase(Locale.ROOT).startsWith(lower);
        return options.filter(matches).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    private ClanService service() {
        return plugin.service();
    }

    private Messages messages() {
        return plugin.messages();
    }

    private PluginConfig config() {
        return plugin.config();
    }
}
