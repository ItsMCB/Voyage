package org.libregalaxy.voyage.core.migration;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finishes a converted player state into vanilla player data
 * (playerdata/&lt;uuid&gt;.dat format / level.dat's Data.Player).
 *
 * <p>Vanilla's contract for player data: the player compound is the ROOT tag
 * (no "Data" wrapper), GZIP-compressed, with a root-level
 * {@code DataVersion} (Int) &gt; 0 — the same "Unknown data version: 0" trap
 * as level.dat. Beyond that, a vanilla load breaks on tags referencing things
 * the exported save does not contain, so normalization:
 * <ul>
 *   <li>stamps/repairs the DataVersion (preserving a valid source value so
 *       the server's data fixers run when re-imported);</li>
 *   <li>writes {@code UUID} as the 4-int array vanilla matches players by;</li>
 *   <li>forces {@code Dimension} (and the respawn dimension) to a dimension
 *       the exported save actually defines — a stale {@code planet:...}
 *       reference fails to load on vanilla;</li>
 *   <li>removes {@code RootVehicle} (references entities that are not part of
 *       the export) and CraftBukkit/Paper bookkeeping tags;</li>
 *   <li>fills {@code Pos}/{@code Rotation} from the player's last known
 *       position, or the world spawn as a last resort.</li>
 * </ul>
 *
 * <p>Casing: 1.20.5-era formats renamed several entity tags to snake_case
 * (Pos/pos, Rotation/rotation, Dimension/dimension). Where this class writes
 * a field itself it writes BOTH spellings with the same value — vanilla
 * ignores keys it does not recognize, so whichever schema 26.2 reads resolves
 * correctly. Tags carried over from {@code storedState} (already
 * vanilla-shaped NBT produced by VanillaPlayerStateConverter) are preserved
 * verbatim.
 */
public final class VanillaPlayerData {

    /**
     * A player's state to attach to an export.
     *
     * @param uuid         the player's UUID — also the playerdata file name
     * @param name         best-effort display name (usercache), nullable
     * @param storedState  the converted vanilla-player-shaped NBT compound
     *                     (inventory, ender chest, health, XP, ...), or null
     *                     when only the position is known
     * @param lastPosition last known position in this world, nullable
     */
    public record ExportedPlayer(
            @NotNull UUID uuid,
            @Nullable String name,
            @Nullable CompoundBinaryTag storedState,
            @Nullable LastPosition lastPosition
    ) {}

    public record LastPosition(double x, double y, double z, float yaw, float pitch) {}

    /** Tags that reference content a vanilla singleplayer save does not have. */
    private static final Set<String> REMOVED_KEYS = Set.of(
            "RootVehicle", "root_vehicle", // entity references not present in the export
            "bukkit", "Paper"              // server/plugin bookkeeping
    );

    private VanillaPlayerData() {}

    /**
     * Normalizes an {@link ExportedPlayer} into a playerdata-format root
     * compound: the player tag IS the root, DataVersion stamped at root level.
     *
     * @param dimensionKey      a dimension id that exists in the exported save
     * @param fallbackPosition  world spawn (used when neither storedState nor
     *                          lastPosition carries a position)
     * @param fallbackDataVersion used when storedState has no valid DataVersion
     */
    public static @NotNull CompoundBinaryTag normalizeForVanilla(
            @NotNull ExportedPlayer player,
            @NotNull String dimensionKey,
            @Nullable double[] fallbackPosition,
            int fallbackDataVersion) {

        CompoundBinaryTag base = player.storedState() != null
                ? player.storedState()
                : CompoundBinaryTag.empty();

        int dataVersion = base.keySet().contains("DataVersion")
                ? base.getInt("DataVersion", 0) : 0;
        if (dataVersion <= 0) dataVersion = fallbackDataVersion;

        CompoundBinaryTag.Builder builder = CompoundBinaryTag.builder();
        for (Map.Entry<String, ? extends BinaryTag> entry : base) {
            if (REMOVED_KEYS.contains(entry.getKey())) continue;
            builder.put(entry.getKey(), entry.getValue());
        }

        builder.putInt("DataVersion", dataVersion);
        builder.put("UUID", IntArrayBinaryTag.intArrayBinaryTag(uuidIntArray(player.uuid())));
        if (player.name() != null && !player.name().isBlank()) {
            builder.putString("name", player.name());
        }

        // The player must spawn inside a dimension the exported save actually
        // defines. Both spellings are written; unknown keys are ignored by
        // vanilla's reader.
        builder.putString("Dimension", dimensionKey);
        builder.putString("dimension", dimensionKey);
        builder.putString("SpawnDimension", dimensionKey);
        builder.putString("spawn_dimension", dimensionKey);

        LastPosition last = player.lastPosition();
        if (last != null) {
            putPosition(builder, last.x(), last.y(), last.z(), last.yaw(), last.pitch());
        } else if (!base.keySet().contains("Pos") && !base.keySet().contains("pos")
                && fallbackPosition != null && fallbackPosition.length >= 3) {
            putPosition(builder, fallbackPosition[0], fallbackPosition[1], fallbackPosition[2], 0f, 0f);
        }
        // else: no position known at all — leave absent and let vanilla derive one.

        return builder.build();
    }

    private static void putPosition(@NotNull CompoundBinaryTag.Builder builder,
                                    double x, double y, double z, float yaw, float pitch) {
        ListBinaryTag.Builder<DoubleBinaryTag> pos = ListBinaryTag.builder(BinaryTagTypes.DOUBLE);
        pos.add(DoubleBinaryTag.doubleBinaryTag(x));
        pos.add(DoubleBinaryTag.doubleBinaryTag(y));
        pos.add(DoubleBinaryTag.doubleBinaryTag(z));
        ListBinaryTag posList = pos.build();
        builder.put("Pos", posList);
        builder.put("pos", posList);

        ListBinaryTag.Builder<FloatBinaryTag> rotation = ListBinaryTag.builder(BinaryTagTypes.FLOAT);
        rotation.add(FloatBinaryTag.floatBinaryTag(yaw));
        rotation.add(FloatBinaryTag.floatBinaryTag(pitch));
        ListBinaryTag rotationList = rotation.build();
        builder.put("Rotation", rotationList);
        builder.put("rotation", rotationList);
    }

    private static int[] uuidIntArray(@NotNull UUID uuid) {
        return new int[]{
                (int) (uuid.getMostSignificantBits() >> 32),
                (int) uuid.getMostSignificantBits(),
                (int) (uuid.getLeastSignificantBits() >> 32),
                (int) uuid.getLeastSignificantBits()
        };
    }
}