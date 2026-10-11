package org.libregalaxy.voyage.core;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.VoyageContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

public class PlayerStateSerializer {

    private final VoyageContext context;

    public PlayerStateSerializer(VoyageContext context) {
        this.context = context;
    }

    public PlayerState capturePlayerState(@NotNull Player player, @NotNull World targetWorld, @Nullable Location exactLocation) {
        PlayerState state = new PlayerState();
        state.inventory = itemStackArrayToBase64(player.getInventory().getContents());
        state.enderChest = itemStackArrayToBase64(player.getEnderChest().getContents());
        state.level = player.getLevel();
        state.exp = player.getExp();
        state.totalExp = player.getTotalExperience();
        state.gameMode = player.getGameMode().name();
        state.potionEffects = potionEffectsToBase64(player.getActivePotionEffects());
        state.foodLevel = player.getFoodLevel();
        state.saturation = player.getSaturation();

        state.fireTicks = player.getFireTicks();
        state.freezeTicks = player.getFreezeTicks();
        state.fallDistance = player.getFallDistance();
        state.remainingAir = player.getRemainingAir();
        state.maximumAir = player.getMaximumAir();

        var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
        state.maxHealth = maxHealth;
        state.health = Math.min(player.getHealth(), maxHealth);

        Location respawn = player.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null) {
            state.respawnWorld = respawn.getWorld().getKey().asString();
            state.respawnX = respawn.getX();
            state.respawnY = respawn.getY();
            state.respawnZ = respawn.getZ();
            state.respawnYaw = respawn.getYaw();
            state.respawnPitch = respawn.getPitch();
        }

        Location loc = exactLocation != null ? exactLocation : player.getLocation();
        if (loc.getWorld() != null && loc.getWorld().equals(targetWorld)) {
            state.lastLocWorld = loc.getWorld().getKey().asString();
            state.lastLocX = loc.getX();
            state.lastLocY = loc.getY();
            state.lastLocZ = loc.getZ();
            state.lastLocYaw = loc.getYaw();
            state.lastLocPitch = loc.getPitch();
        } else {
            state.lastLocWorld = targetWorld.getKey().asString();
            Location spawn = targetWorld.getSpawnLocation();
            state.lastLocX = spawn.getX();
            state.lastLocY = spawn.getY();
            state.lastLocZ = spawn.getZ();
            state.lastLocYaw = spawn.getYaw();
            state.lastLocPitch = spawn.getPitch();
        }

        if (state.worldLocations == null) {
            state.worldLocations = new HashMap<>();
        }
        state.worldLocations.put(state.lastLocWorld, new LocationData(
                state.lastLocX, state.lastLocY, state.lastLocZ, state.lastLocYaw, state.lastLocPitch));

        return state;
    }

    public void applyPlayerState(@NotNull Player player, @NotNull World world, @Nullable PlayerState state, boolean clearIfNull) {
        if (state == null) {
            boolean startFresh = context.getPlugin().getConfig().getBoolean("profiles.start-fresh-on-first-join", true);
            // clearIfNull indicates whether this call is a cross-profile world transition where a fresh slate
            // is eligible to be applied. Server logins and reloads pass clearIfNull = false to prevent wiping
            // existing login gear or starter items.
            if (clearIfNull && startFresh) {
                for (PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                }
                player.getInventory().clear();
                player.getEnderChest().clear();

                var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
                if (maxHealthAttr != null) {
                    try {
                        maxHealthAttr.setBaseValue(20.0);
                    } catch (Exception ignored) {}
                }
                if (!player.isDead()) {
                    player.setHealth(20.0);
                }

                player.setFoodLevel(20);
                player.setSaturation(5.0f);
                player.setLevel(0);
                player.setExp(0.0f);
                player.setTotalExperience(0);

                // Reset environmental and physical ticks so players do not carry damage or fire into fresh profiles
                player.setFireTicks(0);
                player.setFreezeTicks(0);
                player.setFallDistance(0.0f);
                player.setRemainingAir(player.getMaximumAir());
                player.setRespawnLocation(null, true);

                // Do not force Bukkit.getDefaultGameMode() here.
                // The governing world plugin sets the player's game mode
                // based on their group rules, and savePlayerStateAsync persists it below.

                // Immediately persist the newly initialized fresh state to disk for this profile
                context.getService(PlayerDataManager.class).savePlayerStateAsync(player);
            } else {
                // If not clearing (startFresh is false or this is an initial server login), persist current state
                context.getService(PlayerDataManager.class).savePlayerStateAsync(player);
            }
            return;
        }

        // Only wipe potion effects if we are actively applying a new state
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }

        try {
            if (state.inventory != null)
                player.getInventory().setContents(itemStackArrayFromBase64(state.inventory));
            if (state.enderChest != null)
                player.getEnderChest().setContents(itemStackArrayFromBase64(state.enderChest));
        } catch (Exception e) {
            context.getPlugin().getLogger().warning("Failed to restore inventory for " + player.getName());
        }

        var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null && state.maxHealth > 0) {
            try {
                maxHealthAttr.setBaseValue(state.maxHealth);
            } catch (Exception ignored) {
                // Fail silently to prevent crashes from misconfigured attribute bounds
            }
        }

        if (!player.isDead() && state.health > 0) {
            double maxHealth = Math.max(1.0, maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0);
            player.setHealth(Math.clamp(state.health, 1.0, maxHealth));
        }

        player.setFoodLevel(state.foodLevel);
        player.setSaturation(state.saturation);
        player.setLevel(state.level);
        player.setTotalExperience(state.totalExp);

        player.setExp(Math.clamp(state.exp, 0.0f, 1.0f));

        player.setFireTicks(state.fireTicks);
        player.setFreezeTicks(state.freezeTicks);
        player.setFallDistance(state.fallDistance);
        player.setRemainingAir(state.remainingAir);
        player.setMaximumAir(state.maximumAir);

        try {
            if (state.gameMode != null)
                player.setGameMode(GameMode.valueOf(state.gameMode));
        } catch (IllegalArgumentException ignored) {}

        if (state.potionEffects != null) {
            try {
                player.addPotionEffects(potionEffectsFromBase64(state.potionEffects));
            } catch (Exception e) {
                context.getPlugin().getLogger().warning("Failed to restore potion effects for " + player.getName());
            }
        }

        if (state.respawnWorld != null) {
            NamespacedKey bKey = NamespacedKey.fromString(state.respawnWorld);
            if (bKey != null) {
                World bw = Bukkit.getWorld(bKey);
                if (bw != null) {
                    player.setRespawnLocation(new Location(bw, state.respawnX, state.respawnY, state.respawnZ, state.respawnYaw, state.respawnPitch), true);
                } else {
                    player.setRespawnLocation(null, true);
                }
            } else {
                player.setRespawnLocation(null, true);
            }
        } else {
            player.setRespawnLocation(null, true);
        }
    }

    private static String itemStackArrayToBase64(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] itemStackArrayFromBase64(String data) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            return ItemStack.deserializeItemsFromBytes(bytes);
        } catch (Exception e) {
            throw new IOException("Unable to decode item stacks.", e);
        }
    }

    private static String potionEffectsToBase64(Collection<PotionEffect> effects) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            DataOutputStream dataOutput = new DataOutputStream(outputStream);
            dataOutput.writeInt(effects.size());
            for (PotionEffect effect : effects) {
                dataOutput.writeUTF(effect.getType().getKey().asString());
                dataOutput.writeInt(effect.getDuration());
                dataOutput.writeInt(effect.getAmplifier());
                dataOutput.writeBoolean(effect.isAmbient());
                dataOutput.writeBoolean(effect.hasParticles());
                dataOutput.writeBoolean(effect.hasIcon());
                dataOutput.writeBoolean(effect.isInfinite());
            }
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize potion effects.", e);
        }
    }

    private static Collection<PotionEffect> potionEffectsFromBase64(String data) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                 DataInputStream dataInput = new DataInputStream(inputStream)) {
                int size = dataInput.readInt();
                List<PotionEffect> effects = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    NamespacedKey key = NamespacedKey.fromString(dataInput.readUTF());
                    PotionEffectType type = key != null ? Registry.POTION_EFFECT_TYPE.get(key) : null;
                    int duration = dataInput.readInt();
                    int amplifier = dataInput.readInt();
                    boolean ambient = dataInput.readBoolean();
                    boolean particles = dataInput.readBoolean();
                    boolean icon = dataInput.readBoolean();
                    boolean infinite = dataInput.readBoolean();

                    if (type != null) {
                        int finalDuration = infinite ? PotionEffect.INFINITE_DURATION : duration;
                        effects.add(new PotionEffect(type, finalDuration, amplifier, ambient, particles, icon));
                    }
                }
                return effects;
            }
        } catch (Exception e) {
            throw new IOException("Unable to decode potion effects.", e);
        }
    }

    public static class LocationData {
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;

        public LocationData() {}

        public LocationData(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    public static class PlayerState {
        public int schemaVersion = 1;
        public String inventory;
        public String enderChest;
        public double health;
        public double maxHealth;
        public int level;
        public float exp;
        public int totalExp;
        public String gameMode;
        public String potionEffects;
        public int foodLevel = 20;
        public float saturation = 5.0f;
        public String respawnWorld;
        public double respawnX;
        public double respawnY;
        public double respawnZ;
        public float respawnYaw;
        public float respawnPitch;
        public String lastLocWorld;
        public double lastLocX;
        public double lastLocY;
        public double lastLocZ;
        public float lastLocYaw;
        public float lastLocPitch;
        public Map<String, LocationData> worldLocations = new HashMap<>();

        public int fireTicks;
        public int freezeTicks;
        public float fallDistance;
        public int remainingAir;
        public int maximumAir;
    }

    public record PlayerStateCapture(String profileId, UUID uuid, PlayerState state) {}
}