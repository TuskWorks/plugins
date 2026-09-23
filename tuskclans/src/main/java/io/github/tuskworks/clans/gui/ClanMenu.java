package io.github.tuskworks.clans.gui;

import io.github.tuskworks.clans.TuskClans;
import io.github.tuskworks.clans.lang.Messages;
import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanMember;
import io.github.tuskworks.clans.model.ClanRole;
import io.github.tuskworks.clans.service.ClanService;
import io.github.tuskworks.clans.service.Outcome;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.Nullable;

/**
 * Chest menu for /clan menu. Buttons just run the matching /clan subcommand, so the menu
 * never bypasses the rules or messages of the command itself.
 */
public final class ClanMenu implements InventoryHolder {

    private static final int MEMBER_SLOTS = 36;

    private final Map<Integer, String> actions = new HashMap<>();
    private final Inventory inventory;

    private ClanMenu(TuskClans plugin, Player viewer) {
        Messages messages = plugin.messages();
        ClanService service = plugin.service();
        Optional<Clan> clan = service.clanOf(viewer.getUniqueId());
        if (clan.isPresent()) {
            Clan c = clan.get();
            inventory = Bukkit.createInventory(this, 54, plain(messages.render("gui.title-clan",
                    Outcome.vars("tag", c.tag(), "name", c.name()))));
            fillClan(plugin, viewer, c);
        } else {
            inventory = Bukkit.createInventory(this, 27, plain(messages.render("gui.title-none", Map.of())));
            fillNoClan(plugin, viewer);
        }
    }

    public static void open(TuskClans plugin, Player player) {
        player.openInventory(new ClanMenu(plugin, player).getInventory());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public @Nullable String action(int slot) {
        return actions.get(slot);
    }

    private void fillClan(TuskClans plugin, Player viewer, Clan clan) {
        Messages messages = plugin.messages();
        ClanService service = plugin.service();
        ClanRole role = ClanService.roleOf(clan, viewer.getUniqueId());
        int max = service.settings().maxMembers();
        List<ClanMember> members = clan.members().stream()
                .sorted(Comparator.comparing(ClanMember::role, Comparator.reverseOrder())
                        .thenComparing(ClanMember::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        long online = members.stream().filter(m -> Bukkit.getPlayer(m.id()) != null).count();
        String allies = String.join(", ", clan.allies().stream()
                .map(service::byId).flatMap(Optional::stream).map(Clan::tag).toList());
        Map<String, String> info = Outcome.vars(
                "tag", clan.tag(),
                "name", clan.name(),
                "leader", clan.leader().name(),
                "members", String.valueOf(clan.size()),
                "max", max < 0 ? "∞" : String.valueOf(max),
                "online", String.valueOf(online),
                "allies", allies.isEmpty() ? messages.raw("info-none") : allies,
                "kills", String.valueOf(clan.kills()),
                "deaths", String.valueOf(clan.deaths()),
                "kdr", String.format(Locale.ROOT, "%.2f", clan.kdr()),
                "ff", messages.raw(clan.friendlyFire() ? "info-on" : "info-off"),
                "open", messages.raw(clan.open() ? "info-on" : "info-off"));
        put(4, item(messages, Material.WHITE_BANNER, "gui.info", info), null);

        for (int i = 0; i < Math.min(members.size(), MEMBER_SLOTS); i++) {
            put(9 + i, head(messages, members.get(i)), null);
        }
        if (members.size() > MEMBER_SLOTS) {
            put(9 + MEMBER_SLOTS - 1, item(messages, Material.PAPER, "gui.more-members",
                    Outcome.vars("count", String.valueOf(members.size() - MEMBER_SLOTS + 1))), "clan info");
        }

        if (plugin.config().home().enabled()) {
            put(45, item(messages, Material.ENDER_PEARL, clan.home().isPresent() ? "gui.home" : "gui.home-missing",
                    Map.of()), "clan home");
        }
        put(46, item(messages, Material.WRITABLE_BOOK, "gui.chat", Map.of()), "clan chat");
        if (!clan.allies().isEmpty()) {
            put(47, item(messages, Material.FEATHER, "gui.allychat", Map.of()), "clan allychat");
        }
        put(49, item(messages, Material.GOLD_INGOT, "gui.top", Map.of()), "clan top");
        put(50, item(messages, Material.BOOK, "gui.help", Map.of()), "clan help");
        if (role.atLeast(ClanRole.OFFICER)) {
            put(51, item(messages, Material.BLAZE_POWDER, "gui.ff", info), "clan ff");
            put(52, item(messages, Material.OAK_DOOR, "gui.open", info), "clan open");
        }
        if (role == ClanRole.LEADER) {
            put(53, item(messages, Material.TNT, "gui.disband", Map.of()), "clan disband");
        } else {
            put(53, item(messages, Material.BARRIER, "gui.leave", Map.of()), "clan leave");
        }
    }

    private void fillNoClan(TuskClans plugin, Player viewer) {
        Messages messages = plugin.messages();
        ClanService service = plugin.service();
        put(4, item(messages, Material.WHITE_BANNER, "gui.no-clan", Map.of()), null);
        int slot = 9;
        for (Clan invite : service.pendingInvites(viewer.getUniqueId())) {
            if (slot > 17) {
                break;
            }
            put(slot++, item(messages, Material.LIME_BANNER, "gui.invite", clanVars(invite)), "clan accept " + invite.tag());
        }
        slot = 18;
        for (Clan open : service.bySize()) {
            if (slot > 26) {
                break;
            }
            if (open.open()) {
                put(slot++, item(messages, Material.LIGHT_BLUE_BANNER, "gui.open-clan", clanVars(open)),
                        "clan join " + open.tag());
            }
        }
    }

    private void put(int slot, ItemStack item, @Nullable String action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    private static Map<String, String> clanVars(Clan clan) {
        return Outcome.vars("tag", clan.tag(), "name", clan.name(), "members", String.valueOf(clan.size()),
                "leader", clan.leader().name());
    }

    private static ItemStack item(Messages messages, Material material, String key, Map<String, String> vars) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(plain(messages.render(key + ".name", vars)));
            meta.lore(messages.renderList(key + ".lore", vars).stream().map(ClanMenu::plain).toList());
        });
        return stack;
    }

    private static ItemStack head(Messages messages, ClanMember member) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        Player online = Bukkit.getPlayer(member.id());
        Map<String, String> vars = Outcome.vars("player", member.name(),
                "role", messages.raw("role." + member.role().key()));
        stack.editMeta(SkullMeta.class, meta -> {
            if (online != null) {
                meta.setPlayerProfile(online.getPlayerProfile());
            } else {
                meta.setPlayerProfile(Bukkit.createProfile(member.id(), member.name()));
            }
            meta.displayName(plain(messages.render("gui.member.name", vars)));
            meta.lore(messages.renderList(online != null ? "gui.member.lore-online" : "gui.member.lore-offline", vars)
                    .stream().map(ClanMenu::plain).toList());
        });
        return stack;
    }

    /** Item names default to italic; menus look cleaner without it. */
    private static Component plain(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
