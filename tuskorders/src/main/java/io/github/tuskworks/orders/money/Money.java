package io.github.tuskworks.orders.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Money is handled as whole cents in a {@code long} so escrow math never drifts; it is only
 * turned into a {@code double} at the Vault boundary.
 */
public final class Money {

    private static final String[] SUFFIXES = {"", "K", "M", "B", "T"};
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final String symbol;

    public Money(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Parses player input such as {@code 250}, {@code 1,500.25}, {@code $3.5k} or {@code 2m}.
     * Returns empty for anything that isn't a positive amount with at most two decimals.
     */
    public static OptionalLong parse(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (s.startsWith("$")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        BigDecimal multiplier = BigDecimal.ONE;
        char last = s.charAt(s.length() - 1);
        int power = switch (last) {
            case 'k' -> 1;
            case 'm' -> 2;
            case 'b' -> 3;
            case 't' -> 4;
            default -> 0;
        };
        if (power > 0) {
            multiplier = BigDecimal.TEN.pow(3 * power);
            s = s.substring(0, s.length() - 1);
        }
        if (!s.matches("\\d+(\\.\\d+)?|\\.\\d+")) {
            return OptionalLong.empty();
        }
        BigDecimal cents = new BigDecimal(s).multiply(multiplier).multiply(HUNDRED);
        if (cents.signum() <= 0 || cents.stripTrailingZeros().scale() > 0) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(cents.longValueExact());
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    public static double toDouble(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).doubleValue();
    }

    /** Full amount with thousands separators; cents are shown only when non-zero. */
    public String format(long cents) {
        BigDecimal value = BigDecimal.valueOf(cents).movePointLeft(2);
        String pattern = cents % 100 == 0 ? "#,##0" : "#,##0.00";
        return symbol + new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
    }

    /** Short form for item lore, e.g. {@code $12.5K}. */
    public String compact(long cents) {
        BigDecimal value = BigDecimal.valueOf(cents).movePointLeft(2);
        int tier = 0;
        while (tier < SUFFIXES.length - 1 && value.abs().compareTo(BigDecimal.valueOf(1000)) >= 0) {
            value = value.divide(BigDecimal.valueOf(1000));
            tier++;
        }
        if (tier == 0) {
            return format(cents);
        }
        BigDecimal rounded = value.setScale(value.compareTo(BigDecimal.valueOf(100)) >= 0 ? 0 : 1, RoundingMode.DOWN)
                .stripTrailingZeros();
        return symbol + rounded.toPlainString() + SUFFIXES[tier];
    }

    /** {@code percent} of {@code cents}, rounded down so fees never exceed what was stated. */
    public static long percentOf(long cents, double percent) {
        if (percent <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(cents)
                .multiply(BigDecimal.valueOf(percent))
                .divide(HUNDRED, 0, RoundingMode.DOWN)
                .longValueExact();
    }
}
