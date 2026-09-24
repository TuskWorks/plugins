package io.github.tuskworks.orders;

import io.github.tuskworks.orders.command.OrdersCommand;
import io.github.tuskworks.orders.config.PluginConfig;
import io.github.tuskworks.orders.gui.DeliverMenu;
import io.github.tuskworks.orders.gui.MenuListener;
import io.github.tuskworks.orders.hook.VaultBank;
import io.github.tuskworks.orders.item.ItemRules;
import io.github.tuskworks.orders.lang.Messages;
import io.github.tuskworks.orders.listener.PlayerListener;
import io.github.tuskworks.orders.model.Order;
import io.github.tuskworks.orders.prompt.ChatPrompts;
import io.github.tuskworks.orders.prompt.CreateFlow;
import io.github.tuskworks.orders.service.OrderService;
import io.github.tuskworks.orders.service.Outcome;
import io.github.tuskworks.orders.storage.JsonOrderStorage;
import io.github.tuskworks.orders.storage.OrderStorage;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class TuskOrders extends JavaPlugin {

    private static final String LIMIT_PERMISSION = "tuskorders.limit.";

    private volatile PluginConfig config;
    private volatile ItemRules itemRules;
    private Messages messages;
    private VaultBank bank;
    private OrderStorage storage;
    private OrderService service;
    private ChatPrompts prompts;
    private CreateFlow createFlow;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = PluginConfig.from(getConfig(), getLogger());
        itemRules = new ItemRules(config.blacklist());
        messages = new Messages(this);
        messages.load(config.language());
        bank = new VaultBank();

        storage = new JsonOrderStorage(getDataFolder().toPath().resolve("orders"), getLogger());
        service = new OrderService(config.orders(), config.money(), storage, bank, Clock.systemUTC(), getLogger());
        try {
            List<Order> orders = storage.loadAll();
            service.load(orders, storage.loadNextId());
            getLogger().info("Loaded " + orders.size() + " order(s)");
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not load order data, disabling to protect it", e);
            storage.close();
            storage = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        prompts = new ChatPrompts(this, messages);
        createFlow = new CreateFlow(this);
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(prompts, this);
        pm.registerEvents(new PlayerListener(this), this);

        List<String> aliases = config.aliases();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("orders", "Buy orders marketplace", aliases, new OrdersCommand(this)));

        // Economy plugins may register with Vault after us, so check once the server is running
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            if (bank.available()) {
                getLogger().info("Using economy: " + bank.providerName());
            } else {
                getLogger().severe("No Vault economy found; orders are disabled until an economy plugin is installed");
            }
        });
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> expireOrders(), 20L * 60, 20L * 60);
    }

    @Override
    public void onDisable() {
        // Hand in anything sitting in open delivery menus so no items are lost on shutdown
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof DeliverMenu menu) {
                    menu.process(player);
                    player.closeInventory();
                }
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Could not close delivery menu for " + player.getName(), e);
            }
        }
        if (storage != null) {
            storage.close();
        }
    }

    public void reload() {
        reloadConfig();
        PluginConfig fresh = PluginConfig.from(getConfig(), getLogger());
        messages.load(fresh.language());
        itemRules = new ItemRules(fresh.blacklist());
        service.settings(fresh.orders(), fresh.money());
        config = fresh;
    }

    private void expireOrders() {
        for (Order order : service.expireOverdue()) {
            Player owner = Bukkit.getPlayer(order.owner());
            if (owner != null) {
                messages.send(owner, "notify.expired", Outcome.vars(
                        "id", String.valueOf(order.id()),
                        "item_key", order.item(),
                        "refund", service.money().format((long) order.remaining() * order.priceEach())));
            }
        }
    }

    /** Highest {@code tuskorders.limit.<n>} the player has, or the configured default. */
    public int activeOrderLimit(Player player) {
        int limit = config.defaultActiveOrders();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (!info.getValue() || !perm.startsWith(LIMIT_PERMISSION)) {
                continue;
            }
            String value = perm.substring(LIMIT_PERMISSION.length());
            if (value.equals("unlimited")) {
                return Integer.MAX_VALUE;
            }
            try {
                limit = Math.max(limit, Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                // Not a limit node
            }
        }
        return limit;
    }

    public PluginConfig config() {
        return config;
    }

    public ItemRules itemRules() {
        return itemRules;
    }

    public Messages messages() {
        return messages;
    }

    public VaultBank bank() {
        return bank;
    }

    public OrderService service() {
        return service;
    }

    public ChatPrompts prompts() {
        return prompts;
    }

    public CreateFlow createFlow() {
        return createFlow;
    }
}
