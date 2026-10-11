package org.libregalaxy.voyage.api.events;

import org.bukkit.NamespacedKey;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Called after a managed world has been permanently unmounted and removed from disk.
 * This event cannot be cancelled. Dependent plugins hook this event to clean up
 * metadata records safely.
 */
public class VoyageWorldDeletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NamespacedKey worldKey;

    public VoyageWorldDeletedEvent(@NotNull NamespacedKey worldKey) {
        this.worldKey = worldKey;
    }

    public VoyageWorldDeletedEvent(@NotNull NamespacedKey worldKey, boolean isAsync) {
        super(isAsync);
        this.worldKey = worldKey;
    }

    public @NotNull NamespacedKey getWorldKey() {
        return worldKey;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}