package me.itsmcb.voyage.commands;

import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.FormatUtils;
import me.itsmcb.voyage.Voyage;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;

public class ChunkCMD implements CommandExecutor {

    private Voyage instance;

    public ChunkCMD(Voyage instance) {
        this.instance = instance;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("voyage.admin")) {
            Msg.sendResponsive(AudioResponse.ERROR, sender, "&cOops! You lack permission to do this. To use, give yourself the following permission: voyage.admin");
            return true;
        }
        if (!(sender instanceof Player)) {
            Msg.send(sender, "&cOops! Only players can execute this command.");
            return true;
        }
        Player player = (Player) sender;
        CMDHelper cmdHelper = new CMDHelper(args);
        if (cmdHelper.argExists(0)) {
            Chunk chunk = player.getChunk();
            if (cmdHelper.isCalling("info")) {
                // General information
                Msg.send(sender,
                        "&7========== &6 Chunk General Information &7 ==========",
                        FormatUtils.format("Location ( World @ X;Z)", chunk.getWorld().getName()+" @ "+chunk.getX()+";"+ chunk.getZ()),
                        FormatUtils.format("Entities", Integer.toString(chunk.getEntities().length)),
                        FormatUtils.format("Key", Long.toString(chunk.getChunkKey())),
                        FormatUtils.format("Slime Chunk", Boolean.toString(chunk.isSlimeChunk()))
                );
                // Entity information
                // TODO turn into VexelCore method
                HashMap<EntityType, Integer> entityAmounts = new HashMap<>();
                Arrays.stream(chunk.getEntities()).forEach(entity -> {
                    if (entityAmounts.containsKey(entity.getType())) {
                        int amount = entityAmounts.get(entity.getType())+1;
                        entityAmounts.put(entity.getType(), amount);
                    } else {
                        entityAmounts.put(entity.getType(), 1);
                    }
                });
                Msg.send(sender,   "&7========== &6 Chunk Entity Information &7 ==========");
                entityAmounts.forEach((key, value) -> Msg.send(sender, FormatUtils.format(key.name(),Integer.toString(value))));
                // Material information
                // TODO turn into VexelCore method
                HashMap<Material, Integer> materialAmounts = new HashMap<>();
                ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot();
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = -chunk.getWorld().getMinHeight(); y < chunk.getWorld().getMaxHeight(); y++) {
                            Material material = chunkSnapshot.getBlockType(x,y,z);;
                            if (materialAmounts.containsKey(material)) {
                                int amount = materialAmounts.get(material)+1;
                                materialAmounts.put(material, amount);
                            } else {
                                materialAmounts.put(material, 1);
                            }
                        }
                    }
                }
                Msg.send(sender,   "&7========== &6 Chunk Material Information &7 ==========");
                materialAmounts.forEach((key, value) -> Msg.send(sender, FormatUtils.format(key.name(),Integer.toString(value))));
                return true;
            }
        }
        Msg.send(sender,
                "&7========== &6 Chunk Command Help &7 ==========",
                FormatUtils.format("/chunk info", "View chunk information")
        );
        return false;
    }
}
