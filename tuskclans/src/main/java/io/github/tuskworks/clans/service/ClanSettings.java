package io.github.tuskworks.clans.service;

import java.time.Duration;
import java.util.regex.Pattern;

public record ClanSettings(
        int tagMinLength,
        int tagMaxLength,
        Pattern tagPattern,
        int nameMaxLength,
        int maxMembers,
        int maxAllies,
        Duration inviteExpiry,
        boolean defaultFriendlyFire,
        boolean protectAllies) {

    public static ClanSettings defaults() {
        return new ClanSettings(2, 6, Pattern.compile("^[A-Za-z0-9]+$"), 24, 20, 3,
                Duration.ofMinutes(2), false, true);
    }
}
