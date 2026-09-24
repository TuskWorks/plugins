package io.github.tuskworks.crates.util;

import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/** Weighted random selection. Entries with a weight of zero or less are never picked. */
public final class Weighted {

    private Weighted() {
    }

    public static <T> T pick(List<T> entries, ToDoubleFunction<T> weight, RandomGenerator random) {
        double total = totalWeight(entries, weight);
        if (total <= 0) {
            throw new IllegalArgumentException("No entry has a positive weight");
        }
        double roll = random.nextDouble(total);
        T last = null;
        for (T entry : entries) {
            double w = weight.applyAsDouble(entry);
            if (w <= 0) {
                continue;
            }
            last = entry;
            roll -= w;
            if (roll < 0) {
                return entry;
            }
        }
        return last; // floating point edge case
    }

    public static <T> double totalWeight(List<T> entries, ToDoubleFunction<T> weight) {
        double total = 0;
        for (T entry : entries) {
            double w = weight.applyAsDouble(entry);
            if (w > 0) {
                total += w;
            }
        }
        return total;
    }

    /** Chance of {@code entry} being picked, as a percentage between 0 and 100. */
    public static <T> double chancePercent(T entry, List<T> entries, ToDoubleFunction<T> weight) {
        double total = totalWeight(entries, weight);
        double w = weight.applyAsDouble(entry);
        return total <= 0 || w <= 0 ? 0 : w / total * 100.0;
    }
}
