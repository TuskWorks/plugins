package io.github.tuskworks.crates.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouletteTest {

    @Test
    void winnerLandsInTheCenterOfTheFinalWindow() {
        int steps = 32;
        int window = 9;
        List<String> strip = Roulette.strip(steps, window, "WIN", () -> "filler");
        assertEquals(steps + window, strip.size());
        List<String> finalWindow = strip.subList(steps, steps + window);
        assertEquals("WIN", finalWindow.get(window / 2));
    }

    @Test
    void singleSlotWindowEndsOnWinner() {
        int steps = 20;
        List<String> strip = Roulette.strip(steps, 1, "WIN", () -> "filler");
        assertEquals("WIN", strip.get(steps));
    }

    @Test
    void delaysStartFastAndSlowDown() {
        int[] delays = Roulette.stepDelays(32, 8);
        assertEquals(32, delays.length);
        assertEquals(1, delays[0]);
        assertEquals(9, delays[31]);
        for (int i = 1; i < delays.length; i++) {
            assertTrue(delays[i] >= delays[i - 1], "delays must never speed up again");
        }
    }
}
