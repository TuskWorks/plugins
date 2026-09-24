package io.github.tuskworks.e2e.economy;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Locale;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * In-memory economy for e2e runs. Console commands let scenarios set up balances and read
 * them back from the server log:
 * <pre>
 * testeco set &lt;player&gt; &lt;amount&gt;
 * testeco balance &lt;player&gt;        logs "TestEconomy balance &lt;player&gt; = &lt;amount&gt;"
 * testeco fail-deposits &lt;true|false&gt;
 * </pre>
 */
public final class TestEconomyPlugin extends JavaPlugin {

    private final MemoryEconomy economy = new MemoryEconomy();

    @Override
    public void onEnable() {
        getServer().getServicesManager().register(Economy.class, economy, this, ServicePriority.Highest);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("testeco", new Command()));
    }

    private final class Command implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            if (args.length == 3 && args[0].equals("set")) {
                economy.set(args[1], Double.parseDouble(args[2]));
                getLogger().info("balance " + args[1] + " set to " + economy.getBalance(args[1]));
            } else if (args.length == 2 && args[0].equals("balance")) {
                getLogger().info("balance " + args[1] + " = " + String.format(Locale.ROOT, "%.2f",
                        economy.getBalance(args[1])));
            } else if (args.length == 2 && args[0].equals("fail-deposits")) {
                economy.failDeposits(Boolean.parseBoolean(args[1]));
                getLogger().info("fail-deposits " + args[1]);
            } else {
                getLogger().warning("usage: testeco set|balance|fail-deposits ...");
            }
        }

        @Override
        public String permission() {
            return "testeconomy.admin";
        }
    }
}
