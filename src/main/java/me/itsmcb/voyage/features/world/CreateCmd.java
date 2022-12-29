package me.itsmcb.voyage.features.world;

import me.itsmcb.vexelcore.bukkit.api.command.CustomCommand;
import me.itsmcb.vexelcore.bukkit.api.experience.AudioResponse;
import me.itsmcb.vexelcore.bukkit.api.text.BukkitMsgBuilder;
import me.itsmcb.vexelcore.bukkit.api.utils.Msg;
import me.itsmcb.vexelcore.common.api.command.CMDHelper;
import me.itsmcb.voyage.Voyage;
import me.itsmcb.voyage.api.VoyageAPI;
import me.itsmcb.voyage.api.VoyageWorld;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;

public class CreateCmd extends CustomCommand {

    private Voyage instance;

    public CreateCmd(Voyage instance) {
        super("create", "Create worlds", "voyage.admin");
        this.instance = instance;
    }

    @Override
    public void executeAsPlayer(Player player, String[] args) {
        CMDHelper cmdHelper = new CMDHelper(args);
        // TODO world name validation
        // TODO stop if world already exists
        if (!cmdHelper.argExists(0)) {
            help();
            return;
        }
        VoyageWorld voyageWorld = new VoyageWorld(args[0]);
        // TODO msg about how the server may freeze for a second while the initial area generates
        // TODO also send info about how one can customize the world settings

        String seed = cmdHelper.getFlag("--seed");
        if (seed != null) {
            if (!VoyageAPI.isSeed(seed)) {
                Msg.sendResponsive(AudioResponse.ERROR, player, instance.getLocalizationManager().getWithPrefix("world.creation-error-seed"));
                return;
            }
            voyageWorld.setSeed(Long.valueOf(seed));
        }

        String type = cmdHelper.getFlag("--type");
        if (type != null) {
            WorldType worldType = WorldType.getByName(type);
            if (worldType == null) {
                Msg.sendResponsive(AudioResponse.ERROR, player, instance.getLocalizationManager().getWithPrefix("world.creation-error-type"));
                return;
            }
            voyageWorld.setWorldType(worldType);
        }

        String environment = cmdHelper.getFlag("--environment");
        if (environment != null) {
            // TODO check if this actually works (especially with custom dimensions aka data pack dimensions - https://minecraft.fandom.com/wiki/Custom_dimension)
            // Note: Data packs only load in the main world because they affect the whole server by design.
            if (!VoyageAPI.isEnvironment(environment)) {
                Msg.sendResponsive(AudioResponse.ERROR, player, instance.getLocalizationManager().getWithPrefix("world.creation-error-environment"));
                return;
            }
            voyageWorld.setEnvironment(VoyageAPI.getEnvironment(environment));
        }

        String generator = cmdHelper.getFlag("--generator");
        if (generator != null) {
            voyageWorld.setGenerator(generator);
        }

        new BukkitMsgBuilder(instance.getLocalizationManager().getWithPrefix("world.creation-creating")).send(player);
        if (!voyageWorld.load()) {
            // TODO proper error msg
            new BukkitMsgBuilder("&cError occurred!").send(player);
            return;
        }
        // TODO tell player that they were teleported to the new world
        Msg.sendResponsive(AudioResponse.SUCCESS, player, instance.getLocalizationManager().getWithPrefix("world.creation-success"));
        World world = voyageWorld.getWorld();
        Location worldSpawn = world.getSpawnLocation();
        player.teleport(worldSpawn);
    }
}
