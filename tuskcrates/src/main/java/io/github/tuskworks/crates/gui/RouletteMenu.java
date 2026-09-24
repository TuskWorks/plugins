package io.github.tuskworks.crates.gui;

import io.github.tuskworks.crates.TuskCratesPlugin;
import io.github.tuskworks.crates.crate.Reward;
import io.github.tuskworks.crates.open.OpenService;
import io.github.tuskworks.crates.open.OpeningSession;
import io.github.tuskworks.crates.util.Roulette;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Classic horizontal roulette: nine rewards scroll left and slow down until the winner sits in the middle. */
public final class RouletteMenu implements Menu {

    private static final int WINDOW = 9;
    private static final int ROW_START = 9;
    private static final int LINGER_TICKS = 30;
    private static final Sound TICK = Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 0.6f, 1.6f);

    private final TuskCratesPlugin plugin;
    private final Player player;
    private final OpeningSession session;
    private final Inventory inventory;
    private final List<Reward> strip;
    private final int[] delays;

    private ScheduledTask task;
    private int step;
    private int waited;
    private int lingerLeft = -1;

    public RouletteMenu(TuskCratesPlugin plugin, Player player, OpeningSession session) {
        this.plugin = plugin;
        this.player = player;
        this.session = session;
        this.inventory = plugin.getServer().createInventory(this, 27,
                plugin.messages().get("menu.roulette-title", OpenService.crateTag(session.crate())));
        int steps = plugin.settings().rouletteSteps();
        this.strip = Roulette.strip(steps, WINDOW, session.reward(), session.crate()::pickReward);
        this.delays = Roulette.stepDelays(steps, 8);
    }

    /** Must run on the player's thread. */
    public void start() {
        ItemStack border = Items.filler(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack pointer = Items.filler(Material.LIME_STAINED_GLASS_PANE);
        for (int slot = 0; slot < 27; slot++) {
            if (slot < ROW_START || slot >= ROW_START + WINDOW) {
                inventory.setItem(slot, border);
            }
        }
        inventory.setItem(ROW_START - WINDOW + WINDOW / 2, pointer);
        inventory.setItem(ROW_START + WINDOW + WINDOW / 2, pointer);
        render();
        player.openInventory(inventory);
        task = player.getScheduler().runAtFixedRate(plugin, t -> tick(), null, 1, 1);
    }

    private void tick() {
        if (lingerLeft >= 0) {
            if (lingerLeft-- == 0) {
                stop();
                if (player.getOpenInventory().getTopInventory().getHolder(false) == this) {
                    player.closeInventory();
                }
            }
            return;
        }
        if (++waited < delays[step]) {
            return;
        }
        waited = 0;
        step++;
        render();
        player.playSound(TICK);
        if (step == delays.length) {
            plugin.openService().complete(player, session);
            lingerLeft = LINGER_TICKS;
        }
    }

    private void render() {
        for (int i = 0; i < WINDOW; i++) {
            inventory.setItem(ROW_START + i, strip.get(step + i).displayItem());
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        stop();
        // Closing early skips the animation but never the reward.
        plugin.openService().complete(player, session);
    }

    private void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
