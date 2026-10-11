package org.libregalaxy.voyage.listeners;

import me.itsmcb.vexelcore.bukkit.api.framework.TextUtils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.api.portal.PortalLink;
import org.libregalaxy.voyage.api.portal.PortalType;
import org.libregalaxy.voyage.api.portal.generator.VanillaNetherGenerator;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoyagePortalListener implements Listener {

    /**
     * Ticks of portal cooldown applied when Voyage intercepts and handles the teleport
     * itself, preventing the player from immediately re-triggering the portal.
     */
    private static final int PORTAL_COOLDOWN_TICKS = 80;

    private final VoyageAPI api;
    private final JavaPlugin plugin;

    /**
     * UUIDs of players currently waiting for a destination world to load.
     * Entries are added before async loading begins and removed when it completes
     * (success or failure) or when the player disconnects.
     */
    private final Set<UUID> loadingWorldPlayers = ConcurrentHashMap.newKeySet();

    public VoyagePortalListener(@NotNull VoyageAPI api, @NotNull JavaPlugin plugin) {
        this.api = api;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalEnter(@NotNull PlayerPortalEvent event) {
        Location fromLoc = event.getFrom();
        World currentWorld = fromLoc.getWorld();
        if (currentWorld == null) {
            return;
        }

        PortalType type = portalTypeFromCause(event.getCause());
        if (type == null) {
            return;
        }

        Optional<ManagedWorld> optCurrent = api.getManagedWorld(currentWorld.getKey());
        Optional<PortalLink> optLink = optCurrent.flatMap(mw -> mw.getPortalLink(type));

        if (optLink.isEmpty()) {
            // Preserve vanilla behavior for vanilla namespaced dimensions that have no explicit link.
            if (!NamespacedKey.MINECRAFT.equals(currentWorld.getKey().getNamespace())) {
                event.setCancelled(true);
            }
            return;
        }

        PortalLink link = optLink.get();
        Player player = event.getPlayer();

        // Prevent re-entry while the destination is loading.
        if (loadingWorldPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        Optional<ManagedWorld> optTarget = api.getManagedWorld(link.targetWorld());
        if (optTarget.isEmpty()) {
            event.setCancelled(true);
            TextUtils.send(player,
                    " ↪ <red>Destination dimension <white><world></white> is not managed by Voyage.</red>",
                    Placeholder.unparsed("world", link.targetWorld().asString()));
            return;
        }

        ManagedWorld target = optTarget.get();

        if (!target.isLoaded()) {
            // Intercept and handle the load + teleport asynchronously.
            event.setCancelled(true);
            player.setPortalCooldown(PORTAL_COOLDOWN_TICKS);
            loadingWorldPlayers.add(player.getUniqueId());

            TextUtils.send(player,
                    " ↪ <yellow>Loading dimension <white><world></white>, please wait…</yellow>",
                    Placeholder.unparsed("world", link.targetWorld().asString()));

            target.load()
                    .thenAccept(loadedWorld -> {
                        if (!player.isOnline()) {
                            loadingWorldPlayers.remove(player.getUniqueId());
                            return;
                        }

                        Location dest = computeDestination(link, type, fromLoc, loadedWorld);
                        player.teleportAsync(dest).whenComplete((success, ex) ->
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    loadingWorldPlayers.remove(player.getUniqueId());
                                    if (ex != null || !Boolean.TRUE.equals(success)) {
                                        player.setPortalCooldown(PORTAL_COOLDOWN_TICKS);
                                        String err = ex != null ? ex.getMessage() : "teleport returned false";
                                        TextUtils.send(player,
                                                " ↪ <red>Teleport to <white><world></white> failed: <error></red>",
                                                Placeholder.unparsed("world", link.targetWorld().asString()),
                                                Placeholder.unparsed("error", err));
                                    }
                                }));
                    })
                    .exceptionally(ex -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            loadingWorldPlayers.remove(player.getUniqueId());
                            if (player.isOnline()) {
                                TextUtils.send(player,
                                        " ↪ <red>Failed to load dimension <white><world></white>: <error></red>",
                                        Placeholder.unparsed("world", link.targetWorld().asString()),
                                        Placeholder.unparsed("error", ex.getMessage()));
                            }
                        });
                        return null;
                    });
            return;
        }

        // Destination already loaded — handle synchronously.
        World targetBukkit = target.getBukkitWorld().orElse(null);
        if (targetBukkit == null) {
            event.setCancelled(true);
            TextUtils.send(player,
                    " ↪ <red>Destination dimension <white><world></white> failed to resolve. This is a bug — please report it.</red>",
                    Placeholder.unparsed("world", link.targetWorld().asString()));
            return;
        }

        if (type == PortalType.NETHER && link.generator() instanceof VanillaNetherGenerator) {
            // Let Paper's native Nether portal engine build the portal frame;
            // Voyage only redirects the destination.
            event.setTo(link.coordinateStrategy().calculate(fromLoc, targetBukkit));
            event.setCanCreatePortal(true);
        } else {
            event.setCancelled(true);
            player.setPortalCooldown(PORTAL_COOLDOWN_TICKS);
            player.teleportAsync(computeDestination(link, type, fromLoc, targetBukkit));
        }
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        loadingWorldPlayers.remove(event.getPlayer().getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Computes the final teleport destination.
     *
     * Vanilla Nether generation is delegated to Paper only for an actual Nether
     * portal event. A VanillaNetherGenerator used by an END portal link must not
     * receive the native-Nether treatment.
     */
    private static @NotNull Location computeDestination(@NotNull PortalLink link,
                                                        @NotNull PortalType interceptedType,
                                                        @NotNull Location from,
                                                        @NotNull World targetWorld) {
        Location intended = link.coordinateStrategy().calculate(from, targetWorld);

        if (interceptedType == PortalType.NETHER
                && link.generator() instanceof VanillaNetherGenerator) {
            return intended;
        }

        return link.generator().findOrCreate(intended);
    }

    /**
     * Maps a Bukkit teleport cause to a Voyage {@link PortalType}.
     * Returns {@code null} for causes that Voyage does not intercept via this listener.
     */
    @Nullable
    private static PortalType portalTypeFromCause(
            @NotNull PlayerTeleportEvent.TeleportCause cause) {
        return switch (cause) {
            case NETHER_PORTAL -> PortalType.NETHER;
            case END_PORTAL -> PortalType.END;
            default -> null;
        };
    }
}
