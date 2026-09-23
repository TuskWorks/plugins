package io.github.tuskworks.crates.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Pure timing and layout maths for spin-style animations. */
public final class Roulette {

    private Roulette() {
    }

    /**
     * Builds the strip of entries that scrolls past a window of {@code windowSize}.
     * After {@code steps} shifts the window's center holds {@code winner}.
     */
    public static <T> List<T> strip(int steps, int windowSize, T winner, Supplier<T> filler) {
        if (steps < 1 || windowSize < 1) {
            throw new IllegalArgumentException("steps and windowSize must be positive");
        }
        int length = steps + windowSize;
        List<T> strip = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            strip.add(filler.get());
        }
        strip.set(winnerIndex(steps, windowSize), winner);
        return strip;
    }

    public static int winnerIndex(int steps, int windowSize) {
        return steps + windowSize / 2;
    }

    /**
     * Ticks to wait before each step: fast at first, then easing out (cubic)
     * up to {@code 1 + maxExtraDelay} ticks for the final steps.
     */
    public static int[] stepDelays(int steps, int maxExtraDelay) {
        int[] delays = new int[steps];
        for (int i = 0; i < steps; i++) {
            double progress = steps == 1 ? 1 : (double) i / (steps - 1);
            delays[i] = 1 + (int) Math.round(Math.pow(progress, 3) * maxExtraDelay);
        }
        return delays;
    }
}
