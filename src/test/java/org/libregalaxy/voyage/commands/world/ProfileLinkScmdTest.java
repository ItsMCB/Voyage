package org.libregalaxy.voyage.commands.world;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.libregalaxy.voyage.VoyageContext;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.libregalaxy.voyage.core.PlayerDataManager;
import org.libregalaxy.voyage.core.VoyageWorldManager;
import org.libregalaxy.voyage.core.WebhookService;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileLinkScmdTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private MockedStatic<ArgumentTypes> argumentTypesMock;
    private VoyageContext context;
    private VoyageWorldManager worldManager;
    private PlayerDataManager pdm;
    private WebhookService webhookService;
    private ProfileLinkScmd command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Voyage");
        argumentTypesMock = mockStatic(ArgumentTypes.class);
        argumentTypesMock.when(ArgumentTypes::namespacedKey).thenReturn(mock(ArgumentType.class));
        context = mock(VoyageContext.class);
        worldManager = mock(VoyageWorldManager.class);
        pdm = mock(PlayerDataManager.class);
        webhookService = mock(WebhookService.class);

        when(context.getPlugin()).thenReturn(plugin);
        when(context.getWorldManager()).thenReturn(worldManager);
        when(context.getPlayerDataManager()).thenReturn(pdm);
        when(context.getWebhookService()).thenReturn(webhookService);

        command = new ProfileLinkScmd(context);
    }

    @AfterEach
    void tearDown() {
        if (argumentTypesMock != null) {
            argumentTypesMock.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void create_withDryRun_neverMutatesIsolatedTargetWorldProfile() {
        NamespacedKey sourceKey = NamespacedKey.fromString("custom:alpha");
        NamespacedKey targetKey = NamespacedKey.fromString("custom:beta");
        PlayerMock player = server.addPlayer();

        when(worldManager.worldExists(sourceKey)).thenReturn(true);
        when(worldManager.worldExists(targetKey)).thenReturn(true);
        when(worldManager.getWorldDataProfile(sourceKey)).thenReturn("isolated:custom_alpha");
        when(worldManager.getWorldDataProfile(targetKey)).thenReturn("isolated:custom_beta");
        when(worldManager.previewWorldDataProfile(eq(sourceKey), eq("isolated:custom_beta"), any()))
                .thenReturn(new VoyageAPI.DataLinkPreview(1, 0, 1, VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY));

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getArgument("action", String.class)).thenReturn("create");
        when(ctx.getArgument("world", NamespacedKey.class)).thenReturn(sourceKey);
        when(ctx.getArgument("profile_or_clone", String.class)).thenReturn("custom:beta");
        when(ctx.getArgument("flags", String.class)).thenReturn("--dry-run");

        command.execute(ctx, player);

        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performOneTick();

        verify(worldManager, never()).setWorldDataProfile(any(), any(), any());
        verify(worldManager).previewWorldDataProfile(
                eq(sourceKey),
                eq("isolated:custom_beta"),
                eq(VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY)
        );
    }

    @Test
    void create_linkingTwoIsolatedWorlds_awaitsTargetWorldGroupInitBeforeMergingSource() {
        NamespacedKey sourceKey = NamespacedKey.fromString("custom:alpha");
        NamespacedKey targetKey = NamespacedKey.fromString("custom:beta");
        PlayerMock player = server.addPlayer();

        when(worldManager.worldExists(sourceKey)).thenReturn(true);
        when(worldManager.worldExists(targetKey)).thenReturn(true);
        when(worldManager.getWorldDataProfile(sourceKey)).thenReturn("isolated:custom_alpha");
        when(worldManager.getWorldDataProfile(targetKey)).thenReturn("isolated:custom_beta");

        CompletableFuture<Void> targetInitFuture = new CompletableFuture<>();
        AtomicBoolean sourceLinkStarted = new AtomicBoolean(false);

        when(worldManager.setWorldDataProfile(
                eq(targetKey),
                eq("beta"),
                eq(VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY)))
                .thenReturn(targetInitFuture);

        when(worldManager.setWorldDataProfile(
                eq(sourceKey),
                eq("beta"),
                eq(VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY)))
                .thenAnswer(inv -> {
                    sourceLinkStarted.set(true);
                    return CompletableFuture.completedFuture(null);
                });

        @SuppressWarnings("unchecked")
        CommandContext<CommandSourceStack> ctx = mock(CommandContext.class);
        when(ctx.getArgument("action", String.class)).thenReturn("create");
        when(ctx.getArgument("world", NamespacedKey.class)).thenReturn(sourceKey);
        when(ctx.getArgument("profile_or_clone", String.class)).thenReturn("custom:beta");
        when(ctx.getArgument("flags", String.class)).thenReturn("--strategy SOURCE_PRIORITY");

        command.execute(ctx, player);

        // Source world merge must NOT start before targetKey's group initialization future completes
        assertFalse(sourceLinkStarted.get(),
                "Source world merge must wait for target world group initialization to finish");

        targetInitFuture.complete(null);

        assertTrue(sourceLinkStarted.get(),
                "Source world merge must run once target world group initialization completes");
    }
}