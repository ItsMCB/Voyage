package org.libregalaxy.voyage.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.jetbrains.annotations.NotNull;

public class VoyageWorldUnloadEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ManagedWorld world;

    public VoyageWorldUnloadEvent(@NotNull ManagedWorld world) {
        this.world = world;
    }

    public VoyageWorldUnloadEvent(@NotNull ManagedWorld world, boolean isAsync) {
        super(isAsync);
        this.world = world;
    }

    public @NotNull ManagedWorld getManagedWorld() { return world; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}