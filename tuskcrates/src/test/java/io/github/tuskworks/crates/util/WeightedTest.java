package io.github.tuskworks.crates.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeightedTest {

    record Entry(String name, double weight) {
    }

    @Test
    void distributionFollowsWeights() {
        List<Entry> entries = List.of(new Entry("a", 70), new Entry("b", 20), new Entry("c", 10));
        var random = new SplittableRandom(42);
        Map<String, Integer> hits = new HashMap<>();
        int rolls = 200_000;
        for (int i = 0; i < rolls; i++) {
            hits.merge(Weighted.pick(entries, Entry::weight, random).name(), 1, Integer::sum);
        }
        assertEquals(0.70, hits.get("a") / (double) rolls, 0.01);
        assertEquals(0.20, hits.get("b") / (double) rolls, 0.01);
        assertEquals(0.10, hits.get("c") / (double) rolls, 0.01);
    }

    @Test
    void neverPicksNonPositiveWeights() {
        List<Entry> entries = List.of(new Entry("zero", 0), new Entry("negative", -5), new Entry("only", 1));
        var random = new SplittableRandom(7);
        for (int i = 0; i < 10_000; i++) {
            assertEquals("only", Weighted.pick(entries, Entry::weight, random).name());
        }
    }

    @Test
    void rejectsWhenNothingCanBePicked() {
        List<Entry> entries = List.of(new Entry("zero", 0));
        assertThrows(IllegalArgumentException.class,
                () -> Weighted.pick(entries, Entry::weight, new SplittableRandom()));
    }

    @Test
    void chancePercentIgnoresNonPositiveWeights() {
        Entry a = new Entry("a", 3);
        Entry b = new Entry("b", 1);
        Entry disabled = new Entry("off", 0);
        List<Entry> entries = List.of(a, b, disabled);
        assertEquals(75.0, Weighted.chancePercent(a, entries, Entry::weight), 1e-9);
        assertEquals(25.0, Weighted.chancePercent(b, entries, Entry::weight), 1e-9);
        assertEquals(0.0, Weighted.chancePercent(disabled, entries, Entry::weight), 1e-9);
    }
}
