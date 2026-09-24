package io.github.tuskworks.crates.crate;

import java.util.Locale;

public enum AnimationType {
    /** Grant the reward right away. */
    INSTANT,
    /** Spinning roulette menu. */
    ROULETTE,
    /** In-world item display spinning above the crate block. */
    DISPLAY;

    public static AnimationType parse(String value) {
        if (value == null) {
            return ROULETTE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown animation '" + value + "' (use instant, roulette or display)");
        }
    }
}
