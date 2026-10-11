package org.libregalaxy.voyage.api.events;

import org.bukkit.NamespacedKey;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.api.VoyageAPI;

public class VoyageWorldPreLinkEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NamespacedKey worldKey;
    private final String targetProfileId;
    private final VoyageAPI.DataLinkMergeStrategy strategy;
    private boolean cancelled;

    public VoyageWorldPreLinkEvent(@NotNull NamespacedKey worldKey, @NotNull String targetProfileId, @NotNull VoyageAPI.DataLinkMergeStrategy strategy) {
        this.worldKey = worldKey;
        this.targetProfileId = targetProfileId;
        this.strategy = strategy;
    }

    public @NotNull NamespacedKey getWorldKey() { return worldKey; }
    public @NotNull String getTargetProfileId() { return targetProfileId; }
    public @NotNull VoyageAPI.DataLinkMergeStrategy getStrategy() { return strategy; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}