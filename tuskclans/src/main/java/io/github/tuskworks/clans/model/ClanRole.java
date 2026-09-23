package io.github.tuskworks.clans.model;

import java.util.Locale;

public enum ClanRole {
    MEMBER(0),
    OFFICER(1),
    LEADER(2);

    private final int weight;

    ClanRole(int weight) {
        this.weight = weight;
    }

    public boolean atLeast(ClanRole other) {
        return weight >= other.weight;
    }

    public boolean outranks(ClanRole other) {
        return weight > other.weight;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
