package io.github.tuskworks.crates.open;

import io.github.tuskworks.crates.TuskCratesPlugin;
import io.github.tuskworks.crates.crate.Reward;
import io.github.tuskworks.crates.location.BlockPos;
import io.github.tuskworks.crates.util.Roulette;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Spins an item display above the crate, cycling through rewards until it lands on the winner.
 * Everything runs on the region that owns the crate block.
 */
final class DisplayAnimation {

    private static final float SPIN_PER_TICK = 0.3f;

    private final OpenService service;
    private final TuskCratesPlugin plugin;
    private final Player player;
    private final OpeningSession session;
    private final BlockPos pos;
    private final List<Reward> strip;
    private final int[] delays;
    private final int revealTicks;

    private ItemDisplay item;
    private TextDisplay label;
    private int step;
    private int waited;
    private int revealLeft = -1;
    private float angle;

    DisplayAnimation(OpenService service, Player player, OpeningSession session, BlockPos pos) {
        this.service = service;
        this.plugin = service.plugin();
        this.player = player;
        this.session = session;
        this.pos = pos;
        int steps = plugin.settings().displaySteps();
        this.strip = Roulette.strip(steps, 1, session.reward(), session.crate()::pickReward);
        this.delays = Roulette.stepDelays(steps, 6);
        this.revealTicks = plugin.settings().displayRevealTicks();
    }

    void start() {
        Location origin = pos.topCenter().add(0, 0.55, 0);
        plugin.getServer().getRegionScheduler().run(plugin, origin, first -> {
            item = origin.getWorld().spawn(origin, ItemDisplay.class, display -> {
                display.setPersistent(false);
                display.setItemStack(strip.getFirst().displayItem());
                display.setBillboard(Display.Billboard.FIXED);
                display.setTeleportDuration(0);
            });
            plugin.getServer().getRegionScheduler().runAtFixedRate(plugin, origin, this::tick, 1, 1);
        });
    }

    private void tick(ScheduledTask task) {
        if (item == null || !item.isValid()) {
            end(task);
            return;
        }
        if (revealLeft >= 0) {
            spin(0.08f);
            if (revealLeft-- == 0) {
                end(task);
            }
            return;
        }

        spin(SPIN_PER_TICK);
        if (++waited < delays[step]) {
            return;
        }
        waited = 0;
        step++;
        item.setItemStack(strip.get(step).displayItem());
        float pitch = 0.8f + 1.0f * step / delays.length;
        item.getWorld().playSound(Sound.sound(Key.key("block.note_block.hat"), Sound.Source.MASTER, 0.7f, pitch),
                item.getX(), item.getY(), item.getZ());
        if (step == delays.length) {
            reveal();
        }
    }

    private void reveal() {
        Location at = item.getLocation();
        label = at.getWorld().spawn(at.clone().add(0, 0.65, 0), TextDisplay.class, text -> {
            text.setPersistent(false);
            text.text(session.reward().name());
            text.setBillboard(Display.Billboard.CENTER);
            text.setShadowed(true);
        });
        at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 40, 0.3, 0.4, 0.3, 0.25);
        at.getWorld().playSound(Sound.sound(Key.key("entity.firework_rocket.twinkle"), Sound.Source.MASTER, 1f, 1f),
                at.getX(), at.getY(), at.getZ());
        service.completeLater(player, session);
        revealLeft = revealTicks;
    }

    private void spin(float amount) {
        angle += amount;
        item.setInterpolationDelay(0);
        item.setInterpolationDuration(1);
        item.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(angle, 0, 1, 0),
                new Vector3f(0.9f, 0.9f, 0.9f),
                new AxisAngle4f()));
    }

    private void end(ScheduledTask task) {
        task.cancel();
        if (item != null) {
            item.remove();
        }
        if (label != null) {
            label.remove();
        }
        service.unlockBlock(pos);
        // Covers aborted animations (chunk unloaded, entity removed); no-op once granted.
        if (!session.isGranted()) {
            service.completeLater(player, session);
        }
    }
}
