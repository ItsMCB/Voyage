package org.libregalaxy.voyage;

import me.itsmcb.vexelcore.bukkit.api.framework.AbstractVexelContext;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.libregalaxy.voyage.core.PlayerDataManager;
import org.libregalaxy.voyage.core.VoyageWorldManager;
import org.libregalaxy.voyage.core.WebhookService;
import org.libregalaxy.voyage.core.database.VoyageDatabaseService;

public class VoyageContext extends AbstractVexelContext {

    public VoyageContext(@NotNull JavaPlugin plugin) {
        super(plugin);
    }

    public @NotNull VoyageWorldManager getWorldManager() {
        return getService(VoyageWorldManager.class);
    }

    public @NotNull PlayerDataManager getPlayerDataManager() {
        return getService(PlayerDataManager.class);
    }

    public @NotNull WebhookService getWebhookService() {
        return getService(WebhookService.class);
    }

    public @NotNull VoyageDatabaseService getDatabaseService() {
        return getService(VoyageDatabaseService.class);
    }
}