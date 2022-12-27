package me.itsmcb.voyage.features.chunk;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.voyage.Voyage;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;

public class MaterialsCmd extends CustomCommand {

    private Voyage instance;

    public MaterialsCmd(Voyage instance) {
        super("materials", "Chunk materials","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        Chunk chunk = player.getChunk();
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
        Msg.send(player, instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("chunk.material.header-title")));
        materialAmounts.forEach((key, value) -> Msg.send(player, instance.getLocalizationManager().get("result", key.name(), Integer.toString(value))));
    }

    @Override
    public void executeAsConsole(CommandSender console, String[] args) {
        new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("error-player-only-command")).send(console);
    }

    @Override
    public TextComponent permissionError() {
        return new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("error-permission")).get();
    }
}
