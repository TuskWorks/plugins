package io.github.tuskworks.crates.open;

import io.github.tuskworks.crates.TuskCratesPlugin;
import io.github.tuskworks.crates.crate.AnimationType;
import io.github.tuskworks.crates.crate.Crate;
import io.github.tuskworks.crates.crate.Reward;
import io.github.tuskworks.crates.gui.RouletteMenu;
import io.github.tuskworks.crates.location.BlockPos;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OpenService {

    private static final Sound NO_KEY = Sound.sound(Key.key("entity.villager.no"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound REWARD = Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1f, 1.2f);
    private static final long CLICK_DEBOUNCE_MS = 300;

    private final TuskCratesPlugin plugin;
    private final Map<UUID, OpeningSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBlockClick = new ConcurrentHashMap<>();
    private final Set<BlockPos> busyBlocks = ConcurrentHashMap.newKeySet();

    public OpenService(TuskCratesPlugin plugin) {
        this.plugin = plugin;
    }

    /** Right-click on a crate block. Must run on the player's thread. */
    public void openAtBlock(Player player, Crate crate, BlockPos pos) {
        // One click can fire the interact event twice; never spend two keys on it.
        long now = System.currentTimeMillis();
        Long previous = lastBlockClick.put(player.getUniqueId(), now);
        if (previous != null && now - previous < CLICK_DEBOUNCE_MS) {
            return;
        }
        if (!canStart(player)) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean physical = plugin.keys().isKeyFor(hand, crate);
        boolean virtual = !physical && plugin.settings().useVirtualAtBlock()
                && plugin.keyStorage().count(player.getUniqueId(), crate.id()) > 0;
        if (!physical && !virtual) {
            rejectNoKey(player, crate, pos);
            return;
        }
        if (crate.animation() == AnimationType.DISPLAY && busyBlocks.contains(pos)) {
            plugin.messages().send(player, "crate-busy");
            return;
        }
        if (physical) {
            player.getInventory().setItemInMainHand(hand.getAmount() > 1 ? hand.asQuantity(hand.getAmount() - 1) : null);
        } else if (!plugin.keyStorage().take(player.getUniqueId(), crate.id(), 1)) {
            rejectNoKey(player, crate, pos);
            return;
        }
        start(player, crate, crate.animation(), pos);
    }

    /** Opens with a virtual key from anywhere (menu). Must run on the player's thread. */
    public void openVirtual(Player player, Crate crate) {
        if (!canStart(player)) {
            return;
        }
        if (!plugin.keyStorage().take(player.getUniqueId(), crate.id(), 1)) {
            plugin.messages().send(player, "no-key", crateTag(crate));
            player.playSound(NO_KEY);
            return;
        }
        start(player, crate, crate.animation(), null);
    }

    /** Opens without consuming a key (admin command). Must run on the player's thread. */
    public void forceOpen(Player player, Crate crate) {
        if (sessions.containsKey(player.getUniqueId())) {
            plugin.messages().send(player, "already-opening");
            return;
        }
        start(player, crate, crate.animation(), null);
    }

    /** Hands out the reward. Only the first call per session has an effect. Must run on the player's thread. */
    public void complete(Player player, OpeningSession session) {
        if (!session.markGranted()) {
            return;
        }
        sessions.remove(session.playerId(), session);
        Reward reward = session.reward();
        Crate crate = session.crate();

        for (ItemStack item : reward.items()) {
            player.getInventory().addItem(item).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        if (!reward.commands().isEmpty()) {
            List<String> commands = reward.commands().stream()
                    .map(cmd -> cmd.replace("<player>", player.getName()).replace("{player}", player.getName()))
                    .map(cmd -> cmd.startsWith("/") ? cmd.substring(1) : cmd)
                    .toList();
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> commands.forEach(cmd ->
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd)));
        }

        TagResolver tags = TagResolver.resolver(crateTag(crate),
                Placeholder.component("reward", reward.name()),
                Placeholder.unparsed("player", player.getName()));
        plugin.messages().send(player, "reward-received", tags);
        player.playSound(REWARD);
        if (reward.broadcast()) {
            plugin.getServer().forEachAudience(audience -> plugin.messages().send(audience, "reward-broadcast", tags));
        }
    }

    /** Schedules {@link #complete} on the player's thread, e.g. from a region-owned animation. */
    public void completeLater(Player player, OpeningSession session) {
        player.getScheduler().run(plugin, task -> complete(player, session), null);
    }

    /** The player is leaving: grant whatever they were opening right now. */
    public void finishNow(Player player) {
        lastBlockClick.remove(player.getUniqueId());
        OpeningSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            complete(player, session);
        }
    }

    public void shutdown() {
        for (OpeningSession session : List.copyOf(sessions.values())) {
            Player player = plugin.getServer().getPlayer(session.playerId());
            if (player != null) {
                try {
                    complete(player, session);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Could not grant pending reward to " + player.getName() + ": " + e);
                }
            }
        }
        sessions.clear();
    }

    boolean lockBlock(BlockPos pos) {
        return busyBlocks.add(pos);
    }

    void unlockBlock(BlockPos pos) {
        busyBlocks.remove(pos);
    }

    TuskCratesPlugin plugin() {
        return plugin;
    }

    private boolean canStart(Player player) {
        if (!player.hasPermission("tuskcrates.use")) {
            plugin.messages().send(player, "no-permission");
            return false;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            plugin.messages().send(player, "already-opening");
            return false;
        }
        if (plugin.settings().requireEmptySlot() && player.getInventory().firstEmpty() == -1) {
            plugin.messages().send(player, "inventory-full");
            return false;
        }
        return true;
    }

    private void start(Player player, Crate crate, AnimationType animation, BlockPos pos) {
        OpeningSession session = new OpeningSession(player.getUniqueId(), crate, crate.pickReward());
        sessions.put(player.getUniqueId(), session);
        switch (animation) {
            case INSTANT -> complete(player, session);
            case ROULETTE -> new RouletteMenu(plugin, player, session).start();
            case DISPLAY -> {
                if (pos != null && lockBlock(pos)) {
                    new DisplayAnimation(this, player, session, pos).start();
                } else {
                    new RouletteMenu(plugin, player, session).start();
                }
            }
        }
    }

    private void rejectNoKey(Player player, Crate crate, BlockPos pos) {
        plugin.messages().send(player, "no-key", crateTag(crate));
        player.playSound(NO_KEY);
        if (plugin.settings().knockbackWithoutKey()) {
            Vector away = player.getLocation().toVector()
                    .subtract(new Vector(pos.x() + 0.5, player.getLocation().getY(), pos.z() + 0.5));
            if (away.lengthSquared() > 1.0E-4) {
                player.setVelocity(away.normalize().multiply(plugin.settings().knockbackStrength()).setY(0.3));
            }
        }
    }

    public static TagResolver crateTag(Crate crate) {
        return Placeholder.component("crate", crate.displayName());
    }
}
