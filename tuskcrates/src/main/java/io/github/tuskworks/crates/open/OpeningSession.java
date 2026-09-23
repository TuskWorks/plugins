package io.github.tuskworks.crates.open;

import io.github.tuskworks.crates.crate.Crate;
import io.github.tuskworks.crates.crate.Reward;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** A crate being opened. The reward is decided up front; animations only reveal it. */
public final class OpeningSession {

    private final UUID playerId;
    private final Crate crate;
    private final Reward reward;
    private final AtomicBoolean granted = new AtomicBoolean();

    OpeningSession(UUID playerId, Crate crate, Reward reward) {
        this.playerId = playerId;
        this.crate = crate;
        this.reward = reward;
    }

    public UUID playerId() {
        return playerId;
    }

    public Crate crate() {
        return crate;
    }

    public Reward reward() {
        return reward;
    }

    public boolean isGranted() {
        return granted.get();
    }

    /** Returns true exactly once, for whoever gets to hand out the reward. */
    boolean markGranted() {
        return granted.compareAndSet(false, true);
    }
}
