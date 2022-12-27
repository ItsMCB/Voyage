package me.itsmcb.voyage.features.chunk;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.voyage.Voyage;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Chunk;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;

public class EntitiesCmd extends CustomCommand {

    private Voyage instance;

    public EntitiesCmd(Voyage instance) {
        super("entities", "Chunk entities","voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        Chunk chunk = player.getChunk();
        HashMap<EntityType, Integer> entityAmounts = new HashMap<>();
        Arrays.stream(chunk.getEntities()).forEach(entity -> {
            if (entityAmounts.containsKey(entity.getType())) {
                int amount = entityAmounts.get(entity.getType())+1;
                entityAmounts.put(entity.getType(), amount);
            } else {
                entityAmounts.put(entity.getType(), 1);
            }
        });
        Msg.send(player, instance.getLocalizationManager().get("bar", instance.getLocalizationManager().get("chunk.entity.header-title")));
        entityAmounts.forEach((key, value) -> Msg.send(player, instance.getLocalizationManager().get("result", key.name()+"", Integer.toString(value))));
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
