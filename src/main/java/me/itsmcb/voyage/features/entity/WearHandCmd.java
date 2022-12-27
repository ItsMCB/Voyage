package me.itsmcb.voyage.features.entity;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class WearHandCmd extends CustomCommand {

    private Entity entity;

    public WearHandCmd(Entity entity) {
        super("wearhand", "Equip item you're holding to armor stand's head","voyage.admin");
        this.entity = entity;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        if (entity instanceof ArmorStand armorStand) {
            ItemStack main = player.getInventory().getItemInMainHand();
            armorStand.setItem(EquipmentSlot.HEAD, main);
            Msg.send(player, "Gave " + main.translationKey());
            return;
        }
        // TODO msg for non armor stand
    }
}
