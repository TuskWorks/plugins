package io.github.tuskworks.orders.prompt;

import io.github.tuskworks.orders.lang.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Asks a player to type an answer in chat. The answer is kept out of public chat and handed
 * to the callback on the player's own thread, so it can open menus or touch their inventory.
 */
public final class ChatPrompts implements Listener {

    private static final long TIMEOUT_MILLIS = 60_000;

    private record Pending(Consumer<String> answer, long expiresAt) {
    }

    private final Plugin plugin;
    private final Messages messages;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompts(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Replaces any earlier question. Typing {@code cancel} aborts. */
    public void ask(Player player, Consumer<String> answer) {
        pending.put(player.getUniqueId(), new Pending(answer, System.currentTimeMillis() + TIMEOUT_MILLIS));
        messages.send(player, "prompt.hint");
    }

    public void forget(UUID player) {
        pending.remove(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending prompt = pending.remove(player.getUniqueId());
        if (prompt == null || System.currentTimeMillis() > prompt.expiresAt()) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.equalsIgnoreCase("cancel")) {
            messages.send(player, "prompt.cancelled");
            return;
        }
        player.getScheduler().run(plugin, task -> prompt.answer().accept(text), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }
}
