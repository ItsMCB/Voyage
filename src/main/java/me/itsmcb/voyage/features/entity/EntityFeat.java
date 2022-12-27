package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.BukkitFeature;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.voyage.Voyage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.UUID;

public class EntityFeat extends BukkitFeature {

    Voyage instance;
    public HashMap<UUID, UUID> selectedEntities = new HashMap<>();

    public EntityFeat(Voyage instance) {
        super("Entity CMD", "Entity manipulation", null, instance);
        this.instance = instance;
        registerCommand(new EntityCmd(instance, this));
    }

    // Methods
    public void select(Player player, Entity entity) {
        selectedEntities.put(player.getUniqueId(), entity.getUniqueId());
    }

    public Entity getSelected(Player player) {
        if (!validateSelected(player)) {
            return null;
        }
        UUID uuid = selectedEntities.get(player.getUniqueId());
        return Bukkit.getEntity(uuid);
    }

    public boolean validateSelected(Player player) {
        if (!selectedEntities.containsKey(player.getUniqueId())) {
            return false;
        }
        UUID entityUUID = selectedEntities.get(player.getUniqueId());
        Entity entity = Bukkit.getEntity(entityUUID);
        if (entity == null) {
            return false;
        }
        return !entity.isDead();
    }

    public void sendHelp(CommandSender sender) {
        Msg.send(sender,
                instance.getLocalizationManager().get("entity.help.header")
        );
    }

    public String sendEntityInformation(Entity entity) {
        return "&3Type&7: &b"+entity.getType();
    }

}
