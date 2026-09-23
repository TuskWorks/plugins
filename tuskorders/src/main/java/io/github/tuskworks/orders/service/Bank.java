package io.github.tuskworks.orders.service;

import java.util.UUID;

/** The economy as seen by the order service. Amounts are in cents. */
public interface Bank {

    /** Takes money from a player; returns false (and changes nothing) if they can't afford it. */
    boolean withdraw(UUID player, long cents);

    /** Pays a player, online or not; returns false if the economy rejected it. */
    boolean deposit(UUID player, long cents);
}
