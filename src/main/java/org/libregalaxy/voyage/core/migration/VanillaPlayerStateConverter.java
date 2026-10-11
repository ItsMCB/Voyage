package org.libregalaxy.voyage.core.migration;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.libregalaxy.voyage.core.PlayerStateSerializer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Converts a stored {@link PlayerStateSerializer.PlayerState} (Voyage's
 * per-profile player data) into a vanilla player NBT compound. The result is
 * finished by {@link VanillaPlayerData#normalizeForVanilla}, which stamps the
 * DataVersion/UUID/Dimension contract, and then written as
 * playerdata/&lt;uuid&gt;.dat and level.dat's Data.Player.
 *
 * <p>Casing: 1.20.5-era formats renamed several entity tags to snake_case
 * (Health/health, Air/air, Fire/fire, ...). Where this class writes a field
 * itself it emits BOTH spellings with identical values — vanilla ignores keys
 * it does not recognize, so whichever schema 26.2 reads resolves correctly.
 * Tags produced by the server's own serializers (item compounds) are
 * era-correct already and pass through untouched.
 *
 * <p>Respawn points are only carried over when they were set inside the
 * exported world — a respawn in another Voyage dimension cannot exist in a
 * single-dimension vanilla save.
 */
public final class VanillaPlayerStateConverter {

    private static final int MAX_ITEMS = 512;

    private VanillaPlayerStateConverter() {}

    /**
     * @param sourceWorldKey the exported world's key as a string
     *                       ("planet:..."); decides respawn-point inclusion
     */
    public static @NotNull CompoundBinaryTag toVanillaPlayerTag(
            @NotNull PlayerStateSerializer.PlayerState state,
            @NotNull String sourceWorldKey) {

        CompoundBinaryTag.Builder builder = CompoundBinaryTag.builder();

        // --- Items ---------------------------------------------------------
        ListBinaryTag inventory = decodeItemContainers(state.inventory);
        if (inventory != null && inventory.size() > 0) builder.put("Inventory", inventory);
        ListBinaryTag enderItems = decodeItemContainers(state.enderChest);
        if (enderItems != null && enderItems.size() > 0) builder.put("EnderItems", enderItems);

        // --- Vitals --------------------------------------------------------
        builder.putFloat("health", (float) state.health);
        builder.putFloat("Health", (float) state.health);

        if (state.maxHealth > 0 && Math.abs(state.maxHealth - 20.0) > 1.0e-3) {
            // Best-effort modern attribute entry; ignored harmlessly if the
            // shape differs (health above 20 then simply clamps on load).
            builder.put("attributes", CompoundBinaryTag.builder()
                    .put("minecraft:max_health", CompoundBinaryTag.builder()
                            .putDouble("base", state.maxHealth)
                            .build())
                    .build());
        }

        builder.putInt("foodLevel", state.foodLevel);
        builder.putFloat("foodSaturationLevel", state.saturation);

        // --- Experience ----------------------------------------------------
        builder.putInt("XpLevel", state.level);
        builder.putFloat("XpP", state.exp);
        builder.putInt("XpTotal", state.totalExp);

        // --- Game mode -----------------------------------------------------
        Integer gameType = gameModeId(state.gameMode);
        if (gameType != null) {
            builder.putInt("playerGameType", gameType);
        }

        // --- Potion effects (modern active_effects shape) ------------------
        ListBinaryTag effects = decodePotionEffects(state.potionEffects);
        if (effects != null && effects.size() > 0) builder.put("active_effects", effects);

        // --- Environment ---------------------------------------------------
        builder.putInt("fire", state.fireTicks);
        builder.putShort("Fire", (short) state.fireTicks);
        builder.putInt("ticks_frozen", state.freezeTicks);
        builder.putInt("TicksFrozen", state.freezeTicks);
        builder.putFloat("fall_distance", state.fallDistance);
        builder.putFloat("FallDistance", state.fallDistance);
        builder.putInt("air", state.remainingAir);
        builder.putShort("Air", (short) state.remainingAir);

        // --- Respawn point (only if it was in the exported world) ----------
        if (isSameWorld(state.respawnWorld, sourceWorldKey)) {
            int x = (int) Math.floor(state.respawnX);
            int y = (int) Math.floor(state.respawnY);
            int z = (int) Math.floor(state.respawnZ);
            builder.putInt("SpawnX", x);
            builder.putInt("SpawnY", y);
            builder.putInt("SpawnZ", z);
            builder.putInt("spawn_x", x);
            builder.putInt("spawn_y", y);
            builder.putInt("spawn_z", z);
            builder.putFloat("SpawnAngle", state.respawnYaw);
        }
        // Dimension/SpawnDimension are forced by VanillaPlayerData.normalize.

        return builder.build();
    }

    // ---------------------------------------------------------------- items

    /**
     * Decodes the Base64 output of {@code ItemStack.serializeItemsAsBytes}
     * into a vanilla Inventory/EnderItems list: Slot-tagged item compounds.
     *
     * <p>Paper's observed layout is a container header (a version byte, two
     * ints) followed by a GZIP-compressed NBT stream — confirmed by blob
     * inspection: {@code 01 00 00 00 2b 00 00 00 4e 1f 8b ...}. The decoder
     * scans for the gzip magic so the header's exact semantics do not matter,
     * and falls back to every other plausible shape (unprefixed streams,
     * count-prefixed containers).
     *
     * <p>Slot numbers come from the container index (Bukkit inventory indices
     * map to vanilla slots, with armor at 100-103 and the offhand at -106)
     * unless an entry already carries its own Slot. The slot tag is written
     * in both era-native spellings/types: modern {@code slot} (int) and
     * legacy {@code Slot} (byte). Returns null when no shape matches — the
     * caller then exports the player without items and logs a warning
     * including a hex preview for diagnosis.
     */
    static @Nullable ListBinaryTag decodeItemContainers(@Nullable String base64) {
        if (base64 == null || base64.isBlank()) return null;

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        }

        ListBinaryTag raw = readItemTagList(bytes);
        if (raw == null) return null;

        ListBinaryTag.Builder<CompoundBinaryTag> out = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        int index = 0;
        for (BinaryTag element : raw) {
            int slot = index++;
            if (!(element instanceof CompoundBinaryTag item)) continue;
            if (isEmptyItem(item)) continue;

            if (item.keySet().contains("slot")) {
                slot = item.getInt("slot", slot);
            } else if (item.keySet().contains("Slot")) {
                slot = item.getInt("Slot", slot);
            }

            int mapped = toVanillaSlot(slot);
            CompoundBinaryTag.Builder itemBuilder = CompoundBinaryTag.builder().put(item);
            itemBuilder.putInt("slot", mapped);           // modern schema: int
            itemBuilder.putByte("Slot", (byte) mapped);   // legacy schema: byte
            out.add(itemBuilder.build());
        }
        return out.build();
    }

    /** Ways the blob might be compressed, tried in likelihood order. */
    private interface StreamOpener {
        @NotNull InputStream open(@NotNull byte[] bytes) throws IOException;
    }

    /** Paper's gzip opener, reusable for container payloads. */
    private static final StreamOpener GZIP_OPENER =
            bytes -> new GZIPInputStream(new ByteArrayInputStream(bytes));

    private static final List<StreamOpener> OPENERS = List.of(
            GZIP_OPENER,
            bytes -> new InflaterInputStream(new ByteArrayInputStream(bytes)),                      // zlib-wrapped deflate
            bytes -> new InflaterInputStream(new ByteArrayInputStream(bytes), new Inflater(true)),  // raw deflate
            bytes -> new ByteArrayInputStream(bytes)                                                // uncompressed
    );

    private static @Nullable ListBinaryTag readItemTagList(byte[] bytes) {
        // Paper's observed container layout: a version byte, two ints, then
        // the GZIP-compressed NBT (blob head: 01 00 00 00 2b 00 00 00 4e 1f 8b).
        // Scanning for the gzip magic makes the header's exact layout irrelevant.
        int gzipStart = indexOfGzipMagic(bytes);
        if (gzipStart >= 0) {
            byte[] payload = Arrays.copyOfRange(bytes, gzipStart, bytes.length);
            for (boolean namedRoot : new boolean[]{false, true}) {
                ListBinaryTag list = tryReadRootAsItemList(payload, GZIP_OPENER, namedRoot);
                if (list != null) return list;
            }
        }

        // Raw (uncompressed) container headers: try plausible offsets.
        for (int offset : new int[]{9, 5, 1}) {
            for (boolean namedRoot : new boolean[]{false, true}) {
                ListBinaryTag list = tryReadRootAsItemAtOffset(bytes, offset, namedRoot);
                if (list != null) return list;
            }
        }

        // Unprefixed single NBT stream (all compressions and namings).
        for (StreamOpener opener : OPENERS) {
            for (boolean namedRoot : new boolean[]{false, true}) {
                ListBinaryTag list = tryReadRootAsItemList(bytes, opener, namedRoot);
                if (list != null) return list;
            }
        }

        // Count-prefixed containers.
        for (StreamOpener opener : OPENERS) {
            ListBinaryTag list = tryReadContainer(bytes, opener);
            if (list != null) return list;
        }
        return null;
    }

    /** Index of the gzip magic (1f 8b) within the first bytes, or -1. */
    private static int indexOfGzipMagic(byte[] bytes) {
        int limit = Math.min(24, bytes.length - 1);
        for (int i = 0; i <= limit; i++) {
            if (bytes[i] == 0x1f && bytes[i + 1] == (byte) 0x8b) return i;
        }
        return -1;
    }

    /** Root parse starting at a given byte offset (raw, uncompressed data). */
    private static @Nullable ListBinaryTag tryReadRootAsItemAtOffset(byte[] bytes, int offset, boolean namedRoot) {
        if (offset <= 0 || offset >= bytes.length) return null;
        try (DataInputStream data = new DataInputStream(
                new ByteArrayInputStream(bytes, offset, bytes.length - offset))) {
            int rootType = data.readUnsignedByte();
            if (rootType == 0) return null;
            if (namedRoot) data.readUTF(); // skip the root name
            if (rootType == 9) {
                return BinaryTagTypes.LIST.read(data);
            }
            if (rootType == 10) {
                return findListInside(BinaryTagTypes.COMPOUND.read(data));
            }
            return null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static @Nullable ListBinaryTag tryReadRootAsItemList(byte[] bytes, StreamOpener opener, boolean namedRoot) {
        try (DataInputStream data = new DataInputStream(opener.open(bytes))) {
            int rootType = data.readUnsignedByte();
            if (rootType == 0) return null;
            if (namedRoot) data.readUTF(); // skip the root name
            if (rootType == 9) { // TAG_List root — the item list itself
                return BinaryTagTypes.LIST.read(data);
            }
            if (rootType == 10) { // TAG_Compound root — wrapper or single item
                return findListInside(BinaryTagTypes.COMPOUND.read(data));
            }
            return null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static @Nullable ListBinaryTag findListInside(@NotNull CompoundBinaryTag compound) {
        for (Map.Entry<String, ? extends BinaryTag> entry : compound) {
            if (entry.getValue() instanceof ListBinaryTag list) return list;
        }
        return null;
    }

    private static @Nullable ListBinaryTag tryReadContainer(byte[] bytes, StreamOpener opener) {
        // Variant A: [int count] then per item a typed NBT payload.
        ListBinaryTag typed = tryReadTypedContainer(bytes, opener);
        if (typed != null) return typed;
        // Variant B: [int count] then per item [int length][item blob].
        ListBinaryTag prefixed = tryReadLengthPrefixedContainer(bytes, opener);
        if (prefixed != null) return prefixed;
        // Variant C: [int count] then per item a raw self-terminating compound.
        return tryReadRawCompoundContainer(bytes, opener);
    }

    private static @Nullable ListBinaryTag tryReadTypedContainer(byte[] bytes, StreamOpener opener) {
        try (DataInputStream data = new DataInputStream(opener.open(bytes))) {
            int count = data.readInt();
            if (count <= 0 || count > MAX_ITEMS) return null;

            ListBinaryTag.Builder<CompoundBinaryTag> out = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (int i = 0; i < count; i++) {
                int type = data.readUnsignedByte();
                if (type == 0) {
                    out.add(CompoundBinaryTag.empty()); // null-slot placeholder, filtered later
                    continue;
                }
                if (type != 10) return null;
                out.add(BinaryTagTypes.COMPOUND.read(data));
            }
            return out.build();
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static @Nullable ListBinaryTag tryReadLengthPrefixedContainer(byte[] bytes, StreamOpener opener) {
        try (DataInputStream data = new DataInputStream(opener.open(bytes))) {
            int count = data.readInt();
            if (count <= 0 || count > MAX_ITEMS) return null;

            ListBinaryTag.Builder<CompoundBinaryTag> out = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (int i = 0; i < count; i++) {
                int length = data.readInt();
                if (length <= 0 || length > bytes.length) return null;
                byte[] blob = new byte[length];
                data.readFully(blob);

                CompoundBinaryTag item = readSingleItemBlob(blob);
                if (item == null) return null;
                out.add(item);
            }
            return out.build();
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static @Nullable ListBinaryTag tryReadRawCompoundContainer(byte[] bytes, StreamOpener opener) {
        try (DataInputStream data = new DataInputStream(opener.open(bytes))) {
            int count = data.readInt();
            if (count <= 0 || count > MAX_ITEMS) return null;

            ListBinaryTag.Builder<CompoundBinaryTag> out = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (int i = 0; i < count; i++) {
                out.add(BinaryTagTypes.COMPOUND.read(data)); // each payload self-terminates
            }
            return out.build();
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static @Nullable CompoundBinaryTag readSingleItemBlob(byte[] blob) {
        for (StreamOpener opener : OPENERS) {
            for (boolean namedRoot : new boolean[]{false, true}) {
                try (DataInputStream data = new DataInputStream(opener.open(blob))) {
                    int rootType = data.readUnsignedByte();
                    if (rootType == 0) continue;
                    if (namedRoot) data.readUTF();
                    if (rootType == 10) {
                        return BinaryTagTypes.COMPOUND.read(data);
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean isEmptyItem(@NotNull CompoundBinaryTag item) {
        String id = item.keySet().contains("id") ? item.getString("id", "") : "";
        if (id.isBlank() || id.equals("minecraft:air") || id.equals("air")) return true;
        int count = item.keySet().contains("count") ? item.getInt("count", 1)
                : (item.keySet().contains("Count") ? item.getInt("Count", 1) : 1);
        return count <= 0;
    }

    /**
     * Maps Bukkit inventory indices to vanilla equipment slot ids: vanilla
     * player data stores armor at 100-103 and the offhand at -106, while
     * Bukkit reports them as indices 36-40.
     */
    private static int toVanillaSlot(int bukkitIndex) {
        return switch (bukkitIndex) {
            case 36 -> 100;  // boots
            case 37 -> 101;  // leggings
            case 38 -> 102;  // chestplate
            case 39 -> 103;  // helmet
            case 40 -> -106; // offhand
            default -> bukkitIndex; // 0-8 hotbar, 9-35 main, ender 0-26
        };
    }

    // -------------------------------------------------------- potion effects

    /**
     * Parses PlayerStateSerializer's custom potion effect blob (size, then
     * per effect: key, duration, amplifier, ambient, particles, icon,
     * infinite) into modern vanilla active_effects entries.
     */
    private static @Nullable ListBinaryTag decodePotionEffects(@Nullable String base64) {
        if (base64 == null || base64.isBlank()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException invalidBase64) {
            return null;
        }

        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int size = data.readInt();
            if (size < 0 || size > 256) return null; // sanity guard against corrupt blobs

            ListBinaryTag.Builder<CompoundBinaryTag> out = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (int i = 0; i < size; i++) {
                String key = data.readUTF();
                int duration = data.readInt();
                int amplifier = data.readInt();
                boolean ambient = data.readBoolean();
                boolean particles = data.readBoolean();
                boolean icon = data.readBoolean();
                boolean infinite = data.readBoolean();

                String id = key.contains(":") ? key : "minecraft:" + key;
                out.add(CompoundBinaryTag.builder()
                        .putString("id", id)
                        .putInt("amplifier", amplifier)
                        .putInt("duration", infinite ? -1 : duration)
                        .putByte("ambient", (byte) (ambient ? 1 : 0))
                        .putByte("show_particles", (byte) (particles ? 1 : 0))
                        .putByte("show_icon", (byte) (icon ? 1 : 0))
                        .build());
            }
            return out.build();
        } catch (Exception unreadable) {
            return null;
        }
    }

    // ---------------------------------------------------------------- misc

    private static @Nullable Integer gameModeId(@Nullable String gameMode) {
        if (gameMode == null) return null;
        return switch (gameMode.toUpperCase(Locale.ROOT)) {
            case "SURVIVAL" -> 0;
            case "CREATIVE" -> 1;
            case "ADVENTURE" -> 2;
            case "SPECTATOR" -> 3;
            default -> null;
        };
    }

    private static boolean isSameWorld(@Nullable String respawnWorld, @NotNull String sourceWorldKey) {
        if (respawnWorld == null || respawnWorld.isBlank()) return false;
        if (respawnWorld.equalsIgnoreCase(sourceWorldKey)) return true;
        int colon = sourceWorldKey.indexOf(':');
        String value = colon >= 0 ? sourceWorldKey.substring(colon + 1) : sourceWorldKey;
        return sourceWorldKey.startsWith("minecraft:") && respawnWorld.equalsIgnoreCase(value);
    }
}