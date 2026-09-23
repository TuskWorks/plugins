package io.github.tuskworks.clans.hook;

import io.github.tuskworks.clans.TuskClans;
import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanMember;
import io.github.tuskworks.clans.service.ClanService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

/**
 * %tuskclans_...% placeholders. Player placeholders: has_clan, tag, tag_formatted, name,
 * role, leader, members, online, allies, kills, deaths, kdr. Leaderboard:
 * top_&lt;n&gt;_tag|name|kills|deaths|members.
 */
public final class ClanPlaceholders extends PlaceholderExpansion {

    private final TuskClans plugin;

    public ClanPlaceholders(TuskClans plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "tuskclans";
    }

    @Override
    public String getAuthor() {
        return "TuskWorks";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        ClanService service = plugin.service();
        String p = params.toLowerCase(Locale.ROOT);
        if (p.startsWith("top_")) {
            return top(service, p);
        }
        if (player == null) {
            return "";
        }
        Optional<Clan> found = service.clanOf(player.getUniqueId());
        if (p.equals("has_clan")) {
            return String.valueOf(found.isPresent());
        }
        if (found.isEmpty()) {
            return "";
        }
        Clan clan = found.get();
        return switch (p) {
            case "tag" -> clan.tag();
            case "tag_formatted" -> LegacyComponentSerializer.legacySection().serialize(plugin.formattedTag(clan));
            case "name" -> clan.name();
            case "role" -> plugin.messages().raw("role." + ClanService.roleOf(clan, player.getUniqueId()).key());
            case "leader" -> clan.leader().name();
            case "members" -> String.valueOf(clan.size());
            case "online" -> String.valueOf(clan.members().stream()
                    .map(ClanMember::id).filter(id -> Bukkit.getPlayer(id) != null).count());
            case "allies" -> String.join(", ", clan.allies().stream()
                    .map(service::byId).flatMap(Optional::stream).map(Clan::tag).toList());
            case "kills" -> String.valueOf(clan.kills());
            case "deaths" -> String.valueOf(clan.deaths());
            case "kdr" -> String.format(Locale.ROOT, "%.2f", clan.kdr());
            default -> null;
        };
    }

    private static @Nullable String top(ClanService service, String p) {
        String[] parts = p.split("_", 3);
        if (parts.length < 3) {
            return null;
        }
        int rank;
        try {
            rank = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (rank < 1) {
            return null;
        }
        List<Clan> top = service.top(rank);
        if (top.size() < rank) {
            return "";
        }
        Clan clan = top.get(rank - 1);
        return switch (parts[2]) {
            case "tag" -> clan.tag();
            case "name" -> clan.name();
            case "kills" -> String.valueOf(clan.kills());
            case "deaths" -> String.valueOf(clan.deaths());
            case "members" -> String.valueOf(clan.size());
            default -> null;
        };
    }
}
