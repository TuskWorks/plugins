package io.github.tuskworks.crates.key;

import java.util.Locale;

public enum KeyType {
    VIRTUAL,
    PHYSICAL;

    public static KeyType parse(String value, KeyType fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
