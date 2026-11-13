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
import java.util.concurrent.atomic.AtomicLong;

public class PlayerDataManager implements Listener {

    private final VoyageContext context;
    private final PlayerProfileService profileService;
    private final PlayerStateSerializer stateSerializer;

    private final Map<UUID, Location> pendingLocations = new ConcurrentHashMap<>();
    private final Set<UUID> switchingProfiles = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> transitionGenerations = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeLoadedProfiles = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong(0L);

    private record CachedLocationKey(String profileId, UUID playerUuid, String worldKey) {}
    private final Map<CachedLocationKey, VoyageAPI.OfflineLocation> cachedLogOffLocations = new ConcurrentHashMap<>();

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

    public void updateActiveProfile(@NotNull UUID uuid, @NotNull String profileId) {
        activeLoadedProfiles.put(uuid, profileId);
    }

    public void loadAll() {
        profileService.loadAll();
    }

    public void reload() {
        profileService.reload();
        cachedLogOffLocations.clear();

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
                        String profileId = getProfileId(snapshottedWorld.getKey());
                        PlayerStateSerializer.PlayerState state = states.get(uuid);
                        stateSerializer.applyPlayerState(p, snapshottedWorld, state, false);
                        activeLoadedProfiles.put(uuid, profileId);
                        cacheLocationsFromState(profileId, uuid, state);
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
        cachedLogOffLocations.clear();
        return profileService.setProfileId(worldKey, newProfileIdRaw, strategy, progress);
    }

    public CompletableFuture<Void> removeWorldDataLink(NamespacedKey worldKey, boolean cloneData, java.util.function.DoubleConsumer progress) {
        cachedLogOffLocations.clear();
        return profileService.removeWorldDataLink(worldKey, cloneData, progress);
    }

    public CompletableFuture<Void> cloneWorldData(@NotNull NamespacedKey sourceKey, @NotNull NamespacedKey targetKey, java.util.function.DoubleConsumer progress) {
        return profileService.cloneWorldData(sourceKey, targetKey, progress);
    }

    public VoyageAPI.DataLinkPreview previewProfileMerge(NamespacedKey worldKey, String targetProfileIdRaw, VoyageAPI.DataLinkMergeStrategy strategy) {
        return profileService.previewProfileMerge(worldKey, targetProfileIdRaw, strategy);
    }

    public void deleteWorldData(@NotNull NamespacedKey worldKey) {
        cachedLogOffLocations.keySet().removeIf(k -> k.worldKey().equals(worldKey.asString()));
        profileService.deleteWorldData(worldKey);
    }

    public void removeWorldFromGroups(@NotNull NamespacedKey worldKey) {
        cachedLogOffLocations.keySet().removeIf(k -> k.worldKey().equals(worldKey.asString()));
        profileService.removeWorldFromGroups(worldKey);
    }

    public Optional<VoyageAPI.OfflineLocation> getPlayerLogOffLocation(NamespacedKey worldKey, UUID playerUuid) {
        String profileId = getProfileId(worldKey);
        if (profileId != null) {
            VoyageAPI.OfflineLocation cached = cachedLogOffLocations.get(new CachedLocationKey(profileId, playerUuid, worldKey.asString()));
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        Optional<VoyageAPI.OfflineLocation> loaded = profileService.getPlayerLogOffLocation(worldKey, playerUuid);
        if (loaded.isPresent() && profileId != null) {
            cachedLogOffLocations.put(new CachedLocationKey(profileId, playerUuid, worldKey.asString()), loaded.get());
        }
        return loaded;
    }

    public void setPlayerProfileState(@NotNull NamespacedKey worldKey, @NotNull UUID playerUuid, @NotNull PlayerStateSerializer.PlayerState state) {
        String profileId = getProfileId(worldKey);
        cacheLocationsFromState(profileId, playerUuid, state);
        profileService.savePlayerStateSync(profileId, playerUuid, state);
    }

    public void setPlayerProfileStates(@NotNull NamespacedKey worldKey, @NotNull Map<UUID, PlayerStateSerializer.PlayerState> states) {
        String profileId = getProfileId(worldKey);
        for (Map.Entry<UUID, PlayerStateSerializer.PlayerState> entry : states.entrySet()) {
            cacheLocationsFromState(profileId, entry.getKey(), entry.getValue());
        }
        profileService.savePlayerStatesSync(profileId, states);
    }

    /**
     * All players with stored state in this world's data profile. The vanilla
     * world export uses this to write playerdata/&lt;uuid&gt;.dat entries.
     */
    public @NotNull Map<UUID, PlayerStateSerializer.PlayerState> getStoredPlayerStates(@NotNull NamespacedKey worldKey) {
        return profileService.loadAllPlayerStates(worldKey);
    }

    private static boolean isServerStopping() {
        try {
            return Bukkit.isStopping();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void savePlayerState(@NotNull Player player, @NotNull World world, boolean async, @Nullable Location exactLocation) {
        PlayerStateSerializer.PlayerState state = stateSerializer.capturePlayerState(player, world, exactLocation);
        String profileId = getProfileId(world.getKey());
        UUID uuid = player.getUniqueId();

        cacheLocationsFromState(profileId, uuid, state);

        if (async && !isServerStopping()) {
            profileService.savePlayerStateAsync(profileId, uuid, state);
        } else {
            profileService.savePlayerStateSync(profileId, uuid, state);
        }
    }

    private void cacheLocationsFromState(@Nullable String profileId, @NotNull UUID uuid, @Nullable PlayerStateSerializer.PlayerState state) {
        if (profileId == null || state == null) return;
        if (state.worldLocations != null) {
            for (Map.Entry<String, PlayerStateSerializer.LocationData> entry : state.worldLocations.entrySet()) {
                NamespacedKey wk = NamespacedKey.fromString(entry.getKey());
                PlayerStateSerializer.LocationData loc = entry.getValue();
                if (wk != null && loc != null) {
                    cachedLogOffLocations.put(
                            new CachedLocationKey(profileId, uuid, wk.asString()),
                            new VoyageAPI.OfflineLocation(wk, loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
                    );
                }
            }
        }
        if (state.lastLocWorld != null) {
            NamespacedKey wk = NamespacedKey.fromString(state.lastLocWorld);
            if (wk != null) {
                cachedLogOffLocations.put(
                        new CachedLocationKey(profileId, uuid, wk.asString()),
                        new VoyageAPI.OfflineLocation(wk, state.lastLocX, state.lastLocY, state.lastLocZ, state.lastLocYaw, state.lastLocPitch)
                );
            }
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
            String profileId = getProfileId(p.getWorld().getKey());
            PlayerStateSerializer.PlayerState captured = stateSerializer.capturePlayerState(p, p.getWorld(), null);
            cacheLocationsFromState(profileId, p.getUniqueId(), captured);
            captures.add(new PlayerStateSerializer.PlayerStateCapture(profileId, p.getUniqueId(), captured));
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
            try {
                savePlayerState(p, p.getWorld(), false, null);
            } catch (Exception e) {
                context.getPlugin().getLogger().severe("Failed to final-save state for " + p.getName() + ": " + e.getMessage());
            }
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

    private boolean canSaveFromProfile(@NotNull UUID uuid, @Nullable String fromProfile) {
        if (switchingProfiles.contains(uuid)) {
            return false;
        }
        String loadedProfile = activeLoadedProfiles.get(uuid);
        return loadedProfile == null || Objects.equals(loadedProfile, fromProfile);
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
            if (Objects.equals(fromProfile, toProfile)) {
                if (canSaveFromProfile(uuid, fromProfile)) {
                    savePlayerState(player, fromWorld, true, exactLoc);
                }
                return;
            }

            boolean shouldSaveFrom = canSaveFromProfile(uuid, fromProfile);

            long generation = generationSequence.incrementAndGet();
            transitionGenerations.put(uuid, generation);
            switchingProfiles.add(uuid);

            // Only persist fromWorld state if the player's current inventory actually belongs to fromProfile
            if (shouldSaveFrom) {
                savePlayerState(player, fromWorld, true, exactLoc);
            }

            player.closeInventory();

            Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
                PlayerStateSerializer.PlayerState toState = profileService.loadPlayerStateFromFile(toProfile, uuid);

                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    if (!Objects.equals(transitionGenerations.get(uuid), generation)) {
                        return;
                    }
                    try {
                        if (player.isOnline() && Objects.equals(getProfileId(player.getWorld().getKey()), toProfile)) {
                            stateSerializer.applyPlayerState(player, player.getWorld(), toState, true);
                            activeLoadedProfiles.put(uuid, toProfile);
                            cacheLocationsFromState(toProfile, uuid, toState);
                            player.updateInventory();
                        }
                    } finally {
                        if (transitionGenerations.remove(uuid, generation)) {
                            switchingProfiles.remove(uuid);
                        }
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
            transitionGenerations.remove(uuid);
            String loadedProfile = activeLoadedProfiles.remove(uuid);
            stateSerializer.clearPlayerCache(uuid);

            if (switchingProfiles.remove(uuid)) {
                return;
            }
            World currentWorld = event.getPlayer().getWorld();
            String currentProfile = getProfileId(currentWorld.getKey());
            if (loadedProfile != null && !Objects.equals(loadedProfile, currentProfile)) {
                return;
            }
            savePlayerState(event.getPlayer(), currentWorld, true, null);
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

            long generation = generationSequence.incrementAndGet();
            transitionGenerations.put(uuid, generation);
            switchingProfiles.add(uuid);

            Bukkit.getScheduler().runTaskAsynchronously(context.getPlugin(), () -> {
                PlayerStateSerializer.PlayerState state = profileService.loadPlayerStateFromFile(profile, uuid);

                Bukkit.getScheduler().runTask(context.getPlugin(), () -> {
                    if (!Objects.equals(transitionGenerations.get(uuid), generation)) {
                        return;
                    }
                    try {
                        if (player.isOnline() && player.getWorld().equals(world)) {
                            // Never wipe a player's inventory on initial login when state is null (e.g., existing server installation)
                            boolean restoreSavedLocation = (state != null);
                            stateSerializer.applyPlayerState(player, world, state, false, restoreSavedLocation);
                            activeLoadedProfiles.put(uuid, profile);
                            cacheLocationsFromState(profile, uuid, state);
                            player.updateInventory();
                        }
                    } finally {
                        if (transitionGenerations.remove(uuid, generation)) {
                            switchingProfiles.remove(uuid);
                        }
                    }
                });
            });

        } catch (Exception e) {
            UUID uuid = event.getPlayer().getUniqueId();
            transitionGenerations.remove(uuid);
            switchingProfiles.remove(uuid);
            context.getPlugin().getLogger().log(java.util.logging.Level.SEVERE, "Handled data synchronisation error on connect for " + event.getPlayer().getName(), e);
        }
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        List<Player> snapshot = List.copyOf(event.getWorld().getPlayers());
        String worldProfile = getProfileId(event.getWorld().getKey());
        for (Player player : snapshot) {
            if (!canSaveFromProfile(player.getUniqueId(), worldProfile)) continue;
            savePlayerState(player, event.getWorld(), true, null);
        }
    }
}