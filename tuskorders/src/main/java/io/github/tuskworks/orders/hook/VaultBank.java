package io.github.tuskworks.orders.hook;

import io.github.tuskworks.orders.money.Money;
import io.github.tuskworks.orders.service.Bank;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

/**
 * Vault-backed bank. The economy provider is looked up lazily because economy plugins
 * often register it after we enable.
 */
public final class VaultBank implements Bank {

    private volatile @Nullable Economy economy;

    public boolean available() {
        return economy() != null;
    }

    public @Nullable String providerName() {
        Economy eco = economy();
        return eco == null ? null : eco.getName();
    }

    @Override
    public boolean withdraw(UUID player, long cents) {
        Economy eco = economy();
        if (eco == null) {
            return false;
        }
        OfflinePlayer account = Bukkit.getOfflinePlayer(player);
        double amount = Money.toDouble(cents);
        return eco.has(account, amount) && eco.withdrawPlayer(account, amount).transactionSuccess();
    }

    @Override
    public boolean deposit(UUID player, long cents) {
        if (cents == 0) {
            return true;
        }
        Economy eco = economy();
        return eco != null && eco.depositPlayer(Bukkit.getOfflinePlayer(player), Money.toDouble(cents)).transactionSuccess();
    }

    private @Nullable Economy economy() {
        Economy eco = economy;
        if (eco == null) {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider != null) {
                eco = provider.getProvider();
                economy = eco;
            }
        }
        return eco;
    }
}
