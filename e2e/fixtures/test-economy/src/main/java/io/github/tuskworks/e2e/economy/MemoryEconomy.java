package io.github.tuskworks.e2e.economy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;

/** Player accounts keyed by name; AbstractEconomy maps the OfflinePlayer overloads onto these. */
@SuppressWarnings("deprecation") // Vault's name-based methods are what AbstractEconomy builds on
final class MemoryEconomy extends AbstractEconomy {

    private final Map<String, Double> balances = new ConcurrentHashMap<>();
    private volatile boolean failDeposits;

    void set(String player, double amount) {
        balances.put(key(player), amount);
    }

    void failDeposits(boolean fail) {
        failDeposits = fail;
    }

    private static String key(String player) {
        return player.toLowerCase(Locale.ROOT);
    }

    private static EconomyResponse fail(String message) {
        return new EconomyResponse(0, 0, ResponseType.FAILURE, message);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "TestEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return String.format(Locale.ROOT, "$%.2f", amount);
    }

    @Override
    public String currencyNamePlural() {
        return "dollars";
    }

    @Override
    public String currencyNameSingular() {
        return "dollar";
    }

    @Override
    public boolean hasAccount(String player) {
        return true;
    }

    @Override
    public boolean hasAccount(String player, String world) {
        return true;
    }

    @Override
    public double getBalance(String player) {
        return balances.getOrDefault(key(player), 0.0);
    }

    @Override
    public double getBalance(String player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String player, String world, double amount) {
        return has(player, amount);
    }

    @Override
    public synchronized EconomyResponse withdrawPlayer(String player, double amount) {
        if (amount < 0) {
            return fail("negative amount");
        }
        double balance = getBalance(player);
        if (balance < amount) {
            return new EconomyResponse(0, balance, ResponseType.FAILURE, "insufficient funds");
        }
        balances.put(key(player), balance - amount);
        return new EconomyResponse(amount, balance - amount, ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(String player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public synchronized EconomyResponse depositPlayer(String player, double amount) {
        if (failDeposits) {
            return fail("deposits disabled for testing");
        }
        if (amount < 0) {
            return fail("negative amount");
        }
        double balance = getBalance(player) + amount;
        balances.put(key(player), balance);
        return new EconomyResponse(amount, balance, ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(String player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(String player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(String player, String world) {
        return true;
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return fail("no banks");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return fail("no banks");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return fail("no banks");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return fail("no banks");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return fail("no banks");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return fail("no banks");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String player) {
        return fail("no banks");
    }

    @Override
    public EconomyResponse isBankMember(String name, String player) {
        return fail("no banks");
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
