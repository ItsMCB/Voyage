package me.itsmcb.voyage.hooks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.itsmcb.vexelcore.bukkit.api.utils.WorldUtils;
import me.itsmcb.voyage.Voyage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PAPIExpansion extends PlaceholderExpansion {

    private final Voyage instance;

    public PAPIExpansion(Voyage instance) {
        this.instance = instance;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "voyage";
    }

    @Override
    public @NotNull String getAuthor() {
        return "ItsMCB";
    }

    @Override
    public @NotNull String getVersion() {
        return "${version}";
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (params.equalsIgnoreCase("version")){
            return "${version}";
        }
        if (params.equalsIgnoreCase("world_name")){
            return player.getWorld().getName();
        }
        if (params.equalsIgnoreCase("world_seed")){
            return String.valueOf(player.getWorld().getSeed());
        }
        if (params.equalsIgnoreCase("world_border_size")){
            return String.valueOf(player.getWorld().getWorldBorder().getSize());
        }
        if (params.equalsIgnoreCase("world_all_amount")){
            return String.valueOf(WorldUtils.getAmountAllWorlds());
        }
        if (params.equalsIgnoreCase("world_loaded_amount")){
            return String.valueOf(WorldUtils.getLoadedWorlds().size());
        }
        if (params.equalsIgnoreCase("chunk_is_slime_chunk")){
            return Boolean.toString(player.getChunk().isSlimeChunk());
        }
        if (params.equalsIgnoreCase("chunk_X")){
            return Integer.toString(player.getChunk().getX());
        }
        if (params.equalsIgnoreCase("chunk_Z")){
            return Integer.toString(player.getChunk().getZ());
        }
        // TODO "entity_selected" bool and "entity_selected_type" string
        return null;
    }
}
