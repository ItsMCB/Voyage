package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldSaveEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager implements Listener {

    private final VoyageContext context;
    private final PlayerProfileService profileService;
    private final PlayerStateSerializer stateSerializer;

    private final Map<UUID, Location> pendingLocations = new ConcurrentHashMap<>();
    private final Set<UUID> switchingProfiles = ConcurrentHashMap.newKeySet();

    public PlayerDataManager(VoyageContext context, PlayerStateSerializer stateSerializer) {
        this.context = context;
        this.profileService = new PlayerProfileService(context);
        this.stateSerializer = stateSerializer;
    }

    public PlayerStateSerializer getStateSerializer() {
        return stateSerializer;
    }

    public boolean isSwitchingProfiles(@NotNull UUID uuid) {
        return switchingProfiles.contains(uuid);
    }

    public void loadAll() {
        profileService.loadAll();
    }

    public void reload() {
        profileService.reload();

        List<Player> onlineSnapshot = List.copyOf(Bukkit.getOnlinePlayers());
        Map<UUID, World> worldSnapshot = new HashMap<>();
        for (Player p : onlineSnapshot) {
            worldSnapshot.put(p.getUniqueId(), p.getWorld());
        }

        Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            Map<UUID, PlayerStateSerializer.PlayerState> states = new HashMap<>();
            for (Player p : onlineSnapshot) {
                UUID uuid = p.getUniqueId();
                World snapshottedWorld = worldSnapshot.get(uuid);
                if (snapshottedWorld == null) continue;
                String profileId = getProfileId(snapshottedWorld.getKey());
                states.put(uuid, profileService.loadPlayerStateFromFile(profileId, uuid));
            }

            Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID uuid = p.getUniqueId();
                    World snapshottedWorld = worldSnapshot.get(uuid);
                    if (snapshottedWorld == null) continue;
                    if (p.isOnline() && p.getWorld().equals(snapshottedWorld)) {
                        PlayerStateSerializer.PlayerState state = states.get(uuid);
                        stateSerializer.applyPlayerState(p, snapshottedWorld, state, false);
                        p.updateInventory();
                    }
                }
            });
        });
    }

    public void shutdown() {
        profileService.shutdown();
    }

    public String getProfileId(@NotNull NamespacedKey worldKey) {
        return profileService.getProfileId(worldKey);
    }

    public String getDefaultProfileId(@NotNull NamespacedKey worldKey) {
        return profileService.getDefaultProfileId(worldKey);
    }

    public void invalidateProfileCache() {
        profileService.invalidateProfileCache();
    }

    public CompletableFuture<Void> setProfileId(@NotNull NamespacedKey worldKey, String newProfileIdRaw, VoyageAPI.DataLinkMergeStrategy strategy, java.util.function.DoubleConsumer progress) {
        return profileService.setProfileId(worldKey, newProfileIdRaw, strategy, progress);
    }

    public CompletableFuture<Void> removeWorldDataLink(NamespacedKey worldKey, boolean cloneData, java.util.function.DoubleConsumer progress) {
        return profileService.removeWorldDataLink(worldKey, cloneData, progress);
    }

    public CompletableFuture<Void> cloneWorldData(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, java.util.function.DoubleConsumer progress) {
        return profileService.cloneWorldData(sourceKey, targetKey, progress);
    }

    public VoyageAPI.DataLinkPreview previewProfileMerge(NamespacedKey worldKey, String targetProfileIdRaw, VoyageAPI.DataLinkMergeStrategy strategy) {
        return profileService.previewProfileMerge(worldKey, targetProfileIdRaw, strategy);
    }

    public void deleteWorldData(@NotNull NamespacedKey worldKey) {
        profileService.deleteWorldData(worldKey);
    }

    public void removeWorldFromGroups(@NotNull NamespacedKey worldKey) {
        profileService.removeWorldFromGroups(worldKey);
    }

    public Optional<VoyageAPI.OfflineLocation> getPlayerLogOffLocation(NamespacedKey worldKey, UUID playerUuid) {
        return profileService.getPlayerLogOffLocation(worldKey, playerUuid);
    }

    /**
     * All players with stored state in this world's data profile. The vanilla
     * world export uses this to write playerdata/&lt;uuid&gt;.dat entries.
     */
    public @NotNull Map<UUID, PlayerStateSerializer.PlayerState> getStoredPlayerStates(@NotNull NamespacedKey worldKey) {
        return profileService.loadAllPlayerStates(worldKey);
    }

    public void savePlayerState(@NotNull Player player, @NotNull World world, boolean async, @Nullable Location exactLocation) {
        PlayerStateSerializer.PlayerState state = stateSerializer.capturePlayerState(player, world, exactLocation);
        String profileId = getProfileId(world.getKey());
        UUID uuid = player.getUniqueId();

        if (async && !Bukkit.isStopping()) {
            profileService.savePlayerStateAsync(profileId, uuid, state);
        } else {
            profileService.savePlayerStateSync(profileId, uuid, state);
        }
    }

    public void savePlayerStateAsync(@NotNull Player player) {
        savePlayerState(player, player.getWorld(), true, null);
    }

    public void savePlayerStateSync(@NotNull Player player) {
        savePlayerState(player, player.getWorld(), false, null);
    }

    public void saveAllOnline() {
        List<Player> snapshot = List.copyOf(Bukkit.getOnlinePlayers());
        List<PlayerStateSerializer.PlayerStateCapture> captures = new ArrayList<>();
        for (Player p : snapshot) {
            if (switchingProfiles.contains(p.getUniqueId())) continue;
            captures.add(new PlayerStateSerializer.PlayerStateCapture(getProfileId(p.getWorld().getKey()), p.getUniqueId(), stateSerializer.capturePlayerState(p, p.getWorld(), null)));
        }
        Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
            for (PlayerStateSerializer.PlayerStateCapture capture : captures) {
                profileService.savePlayerStateSync(capture.profileId(), capture.uuid(), capture.state());
            }
        });
    }

    public void saveAllOnlineSync() {
        List<Player> snapshot = List.copyOf(Bukkit.getOnlinePlayers());
        for (Player p : snapshot) {
            if (switchingProfiles.contains(p.getUniqueId())) continue;
            savePlayerState(p, p.getWorld(), false, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getWorld() == event.getTo().getWorld()) return;

        UUID uuid = event.getPlayer().getUniqueId();
        pendingLocations.put(uuid, event.getFrom());

        Bukkit.getScheduler().runTaskLater(context.getPlugin(), () -> pendingLocations.remove(uuid), 20L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropDuringTransition(PlayerDropItemEvent event) {
        if (switchingProfiles.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClickDuringTransition(InventoryClickEvent event) {
        if (switchingProfiles.contains(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDragDuringTransition(InventoryDragEvent event) {
        if (switchingProfiles.contains(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpenDuringTransition(InventoryOpenEvent event) {
        if (switchingProfiles.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractDuringTransition(PlayerInteractEvent event) {
        if (switchingProfiles.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlaceDuringTransition(BlockPlaceEvent event) {
        if (switchingProfiles.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHandSwapDuringTransition(PlayerSwapHandItemsEvent event) {
        if (switchingProfiles.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupDuringTransition(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && switchingProfiles.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathDuringTransition(PlayerDeathEvent event) {
        if (switchingProfiles.contains(event.getEntity().getUniqueId())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        try {
            Player player = event.getPlayer();
            World toWorld = player.getWorld();
            World fromWorld = event.getFrom();

            String fromProfile = getProfileId(fromWorld.getKey());
            String toProfile = getProfileId(toWorld.getKey());

            UUID uuid = player.getUniqueId();
            Location exactLoc = pendingLocations.remove(uuid);

            // Preserve last known coordinates in fromWorld when moving between worlds within the same profile group
            if (fromProfile.equals(toProfile)) {
                savePlayerState(player, fromWorld, true, exactLoc);
                return;
            }

            switchingProfiles.add(uuid);

            savePlayerState(player, fromWorld, true, exactLoc);

            player.closeInventory();

            Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
                PlayerStateSerializer.PlayerState toState = profileService.loadPlayerStateFromFile(toProfile, uuid);

                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    try {
                        if (player.isOnline() && player.getWorld().equals(toWorld)) {
                            stateSerializer.applyPlayerState(player, toWorld, toState, true);
                            player.updateInventory();
                        }
                    } finally {
                        switchingProfiles.remove(uuid);
                    }
                });
            });

        } catch (Exception e) {
            context.getPlugin().getLogger().log(java.util.logging.Level.SEVERE, "Failed to swap inventory state for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            UUID uuid = event.getPlayer().getUniqueId();
            pendingLocations.remove(uuid);
            if (switchingProfiles.remove(uuid)) {
                return;
            }
            savePlayerState(event.getPlayer(), event.getPlayer().getWorld(), true, null);
        } catch (Exception e) {
            context.getPlugin().getLogger().log(java.util.logging.Level.SEVERE, "Failed data save on disconnect for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            World world = player.getWorld();
            String profile = getProfileId(world.getKey());
            UUID uuid = player.getUniqueId();

            boolean clearIfNull = player.hasPlayedBefore();

            Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
                PlayerStateSerializer.PlayerState state = profileService.loadPlayerStateFromFile(profile, uuid);

                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    if (player.isOnline() && player.getWorld().equals(world)) {
                        stateSerializer.applyPlayerState(player, world, state, clearIfNull);
                        player.updateInventory();
                    }
                });
            });

        } catch (Exception e) {
            context.getPlugin().getLogger().log(java.util.logging.Level.SEVERE, "Handled data synchronisation error on connect for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        List<Player> snapshot = List.copyOf(event.getWorld().getPlayers());
        for (Player player : snapshot) {
            if (switchingProfiles.contains(player.getUniqueId())) continue;
            savePlayerState(player, event.getWorld(), true, null);
        }
    }
}