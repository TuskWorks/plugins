package io.github.tuskworks.orders.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IconsTest {

    @Test
    void timeLeftDropsZeroParts() {
        assertEquals("7d", Icons.timeLeft(Duration.ofDays(7)));
        assertEquals("3d 4h", Icons.timeLeft(Duration.ofDays(3).plusHours(4).plusMinutes(30)));
        assertEquals("12h", Icons.timeLeft(Duration.ofHours(12)));
        assertEquals("5h 12m", Icons.timeLeft(Duration.ofHours(5).plusMinutes(12)));
        assertEquals("42m", Icons.timeLeft(Duration.ofMinutes(42)));
    }

    @Test
    void timeLeftNeverShowsZeroForTimeStillLeft() {
        assertEquals("1m", Icons.timeLeft(Duration.ofSeconds(20)));
        assertEquals("0m", Icons.timeLeft(Duration.ZERO));
        assertEquals("0m", Icons.timeLeft(Duration.ofMinutes(-5)));
    }
}
