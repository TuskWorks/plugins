package io.github.tuskworks.orders.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private final Money money = new Money("$");

    @ParameterizedTest
    @CsvSource({
            "250, 25000",
            "'1,500.25', 150025",
            "$3.5k, 350000",
            "2M, 200000000",
            "0.01, 1",
            ".5, 50",
            "1_000, 100000",
    })
    void parsesPlayerInput(String input, long cents) {
        assertEquals(OptionalLong.of(cents), Money.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-5", "abc", "1.234", "1e5", "5kk", "$", "k", "99999999999999999999t"})
    void rejectsInvalidInput(String input) {
        assertTrue(Money.parse(input).isEmpty(), input);
    }

    @Test
    void formatsWholeAndFractionalAmounts() {
        assertEquals("$1,234", money.format(123_400));
        assertEquals("$1,234.50", money.format(123_450));
        assertEquals("$0.01", money.format(1));
    }

    @Test
    void compactsLargeAmounts() {
        assertEquals("$999", money.compact(999_00));
        assertEquals("$1K", money.compact(1_000_00));
        assertEquals("$12.3K", money.compact(12_345_67));
        assertEquals("$250K", money.compact(250_000_00));
        assertEquals("$1.5M", money.compact(1_500_000_00));
    }

    @Test
    void percentRoundsDown() {
        assertEquals(999, Money.percentOf(9_999, 10));
        assertEquals(0, Money.percentOf(9_999, 0));
        assertEquals(1, Money.percentOf(3, 50));
    }
}
