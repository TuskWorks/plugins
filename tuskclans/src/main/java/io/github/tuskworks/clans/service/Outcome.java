package io.github.tuskworks.clans.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of a clan action, expressed as a message key plus placeholder values so the
 * service layer stays free of Bukkit and chat formatting.
 */
public record Outcome(boolean success, String key, Map<String, String> vars) {

    public static Outcome ok(String key, String... kv) {
        return new Outcome(true, key, vars(kv));
    }

    public static Outcome fail(String key, String... kv) {
        return new Outcome(false, key, vars(kv));
    }

    public static Map<String, String> vars(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders must be key/value pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
