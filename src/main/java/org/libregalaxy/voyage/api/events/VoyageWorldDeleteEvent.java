package org.libregalaxy.voyage.api.events;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.jetbrains.annotations.NotNull;

/**
 * Called before a managed world is permanently deleted from disk.
 * Cancelling this event prevents the deletion; the world remains tracked and loaded (if it was loaded).
 */
public class VoyageWorldDeleteEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ManagedWorld world;
    private boolean cancelled;

    public VoyageWorldDeleteEvent(@NotNull ManagedWorld world) {
        this.world = world;
    }

    public VoyageWorldDeleteEvent(@NotNull ManagedWorld world, boolean isAsync) {
        super(isAsync);
        this.world = world;
    }

    public @NotNull ManagedWorld getManagedWorld() { return world; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}