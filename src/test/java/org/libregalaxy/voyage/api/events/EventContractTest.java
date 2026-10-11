package org.libregalaxy.voyage.api.events;

import org.bukkit.NamespacedKey;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.libregalaxy.voyage.api.ManagedWorld;
import org.libregalaxy.voyage.api.VoyageAPI;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventContractTest {

    private ServerMock server;
    private ManagedWorld mockWorld;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        mockWorld = mock(ManagedWorld.class);
        when(mockWorld.getKey()).thenReturn(NamespacedKey.fromString("custom:test"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ─── VoyageWorldLoadEvent ───

    @Nested
    class VoyageWorldLoadEventTests {
        @Test
        void syncConstructor_setsAsyncToFalse() {
            VoyageWorldLoadEvent event = new VoyageWorldLoadEvent(mockWorld);
            assertFalse(event.isAsynchronous());
        }

        @Test
        void asyncConstructor_setsAsyncToTrue() {
            VoyageWorldLoadEvent event = new VoyageWorldLoadEvent(mockWorld, true);
            assertTrue(event.isAsynchronous());
        }

        @Test
        void getManagedWorld_returnsCorrectWorld() {
            VoyageWorldLoadEvent event = new VoyageWorldLoadEvent(mockWorld);
            assertSame(mockWorld, event.getManagedWorld());
        }

        @Test
        void getHandlers_returnsSameInstanceAsStatic() {
            VoyageWorldLoadEvent event = new VoyageWorldLoadEvent(mockWorld);
            HandlerList instanceHandlers = event.getHandlers();
            HandlerList staticHandlers = VoyageWorldLoadEvent.getHandlerList();
            assertSame(staticHandlers, instanceHandlers,
                    "Instance getHandlers() and static getHandlerList() must return the same HandlerList");
        }
    }

    // ─── VoyageWorldUnloadEvent ───

    @Nested
    class VoyageWorldUnloadEventTests {
        @Test
        void syncConstructor_setsAsyncToFalse() {
            VoyageWorldUnloadEvent event = new VoyageWorldUnloadEvent(mockWorld);
            assertFalse(event.isAsynchronous());
        }

        @Test
        void asyncConstructor_setsAsyncToTrue() {
            VoyageWorldUnloadEvent event = new VoyageWorldUnloadEvent(mockWorld, true);
            assertTrue(event.isAsynchronous());
        }

        @Test
        void getManagedWorld_returnsCorrectWorld() {
            VoyageWorldUnloadEvent event = new VoyageWorldUnloadEvent(mockWorld);
            assertSame(mockWorld, event.getManagedWorld());
        }

        @Test
        void handlerListIsAccessible() {
            assertNotNull(VoyageWorldUnloadEvent.getHandlerList());
        }
    }

    // ─── VoyageWorldCloneEvent ───

    @Nested
    class VoyageWorldCloneEventTests {
        @Test
        void syncConstructor_setsAsyncToFalse() {
            VoyageWorldCloneEvent event = new VoyageWorldCloneEvent(mockWorld);
            assertFalse(event.isAsynchronous());
        }

        @Test
        void asyncConstructor_setsAsyncToTrue() {
            VoyageWorldCloneEvent event = new VoyageWorldCloneEvent(mockWorld, true);
            assertTrue(event.isAsynchronous());
        }

        @Test
        void getManagedWorld_returnsCorrectWorld() {
            VoyageWorldCloneEvent event = new VoyageWorldCloneEvent(mockWorld);
            assertSame(mockWorld, event.getManagedWorld());
        }

        @Test
        void handlerListIsAccessible() {
            assertNotNull(VoyageWorldCloneEvent.getHandlerList());
        }
    }

    // ─── VoyageWorldPreLoadEvent (Cancellable) ───

    @Nested
    class VoyageWorldPreLoadEventTests {
        @Test
        void defaultsToNotCancelled() {
            VoyageWorldPreLoadEvent event = new VoyageWorldPreLoadEvent(mockWorld);
            assertFalse(event.isCancelled());
        }

        @Test
        void setCancelledTrue_persists() {
            VoyageWorldPreLoadEvent event = new VoyageWorldPreLoadEvent(mockWorld);
            event.setCancelled(true);
            assertTrue(event.isCancelled());
        }

        @Test
        void setCancelledTrue_thenFalse_resets() {
            VoyageWorldPreLoadEvent event = new VoyageWorldPreLoadEvent(mockWorld);
            event.setCancelled(true);
            event.setCancelled(false);
            assertFalse(event.isCancelled());
        }

        @Test
        void asyncConstructor_setsAsyncToTrue() {
            VoyageWorldPreLoadEvent event = new VoyageWorldPreLoadEvent(mockWorld, true);
            assertTrue(event.isAsynchronous());
        }

        @Test
        void getManagedWorld_returnsCorrectWorld() {
            VoyageWorldPreLoadEvent event = new VoyageWorldPreLoadEvent(mockWorld);
            assertSame(mockWorld, event.getManagedWorld());
        }

        @Test
        void handlerListIsAccessible() {
            assertNotNull(VoyageWorldPreLoadEvent.getHandlerList());
        }
    }

    // ─── VoyageWorldDeleteEvent (Cancellable) ───

    @Nested
    class VoyageWorldDeleteEventTests {
        @Test
        void defaultsToNotCancelled() {
            VoyageWorldDeleteEvent event = new VoyageWorldDeleteEvent(mockWorld);
            assertFalse(event.isCancelled());
        }

        @Test
        void setCancelledTrue_persists() {
            VoyageWorldDeleteEvent event = new VoyageWorldDeleteEvent(mockWorld);
            event.setCancelled(true);
            assertTrue(event.isCancelled());
        }

        @Test
        void setCancelledTrue_thenFalse_resets() {
            VoyageWorldDeleteEvent event = new VoyageWorldDeleteEvent(mockWorld);
            event.setCancelled(true);
            event.setCancelled(false);
            assertFalse(event.isCancelled());
        }

        @Test
        void asyncConstructor_setsAsyncToTrue() {
            VoyageWorldDeleteEvent event = new VoyageWorldDeleteEvent(mockWorld, true);
            assertTrue(event.isAsynchronous());
        }

        @Test
        void getManagedWorld_returnsCorrectWorld() {
            VoyageWorldDeleteEvent event = new VoyageWorldDeleteEvent(mockWorld);
            assertSame(mockWorld, event.getManagedWorld());
        }

        @Test
        void handlerListIsAccessible() {
            assertNotNull(VoyageWorldDeleteEvent.getHandlerList());
        }
    }

    // ─── VoyageWorldPreLinkEvent (Cancellable) ───

    @Nested
    class VoyageWorldPreLinkEventTests {
        @Test
        void defaultsToNotCancelled() {
            NamespacedKey key = NamespacedKey.fromString("custom:world");
            VoyageWorldPreLinkEvent event = new VoyageWorldPreLinkEvent(
                    key, "group:survival", VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY);
            assertFalse(event.isCancelled());
        }

        @Test
        void setCancelledTrue_persists() {
            NamespacedKey key = NamespacedKey.fromString("custom:world");
            VoyageWorldPreLinkEvent event = new VoyageWorldPreLinkEvent(
                    key, "group:survival", VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY);
            event.setCancelled(true);
            assertTrue(event.isCancelled());
        }

        @Test
        void setCancelledTrue_thenFalse_resets() {
            NamespacedKey key = NamespacedKey.fromString("custom:world");
            VoyageWorldPreLinkEvent event = new VoyageWorldPreLinkEvent(
                    key, "group:survival", VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY);
            event.setCancelled(true);
            event.setCancelled(false);
            assertFalse(event.isCancelled());
        }

        @Test
        void getWorldKey_returnsCorrectKey() {
            NamespacedKey key = NamespacedKey.fromString("custom:world");
            VoyageWorldPreLinkEvent event = new VoyageWorldPreLinkEvent(
                    key, "group:survival", VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY);
            assertEquals(key, event.getWorldKey());
        }

        @Test
        void getTargetProfileId_returnsCorrectId() {
            NamespacedKey key = NamespacedKey.fromString("custom:world");
            VoyageWorldPreLinkEvent event = new VoyageWorldPreLinkEvent(
                    key, "group:survival", VoyageAPI.DataLinkMergeStrategy.SOURCE_PRIORITY);
            assertEquals("group:survival", event.getTargetProfileId());
        }

        @Test
        void getStrategy_returnsCorrectStrategy() {
            NamespacedKey key = NamespacedKey.fromString("custom:world");
            VoyageWorldPreLinkEvent event = new VoyageWorldPreLinkEvent(
                    key, "group:survival", VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY);
            assertEquals(VoyageAPI.DataLinkMergeStrategy.DESTINATION_PRIORITY, event.getStrategy());
        }

        @Test
        void handlerListIsAccessible() {
            assertNotNull(VoyageWorldPreLinkEvent.getHandlerList());
        }
    }

    // ─── Cross-event validation ───

    @Test
    void allEventHandlerLists_areDistinct() {
        // Each event class must have its own static HandlerList (Bukkit requirement)
        HandlerList loadList = VoyageWorldLoadEvent.getHandlerList();
        HandlerList unloadList = VoyageWorldUnloadEvent.getHandlerList();
        HandlerList cloneList = VoyageWorldCloneEvent.getHandlerList();
        HandlerList preLoadList = VoyageWorldPreLoadEvent.getHandlerList();
        HandlerList deleteList = VoyageWorldDeleteEvent.getHandlerList();
        HandlerList preLinkList = VoyageWorldPreLinkEvent.getHandlerList();

        assertNotSame(loadList, unloadList);
        assertNotSame(loadList, cloneList);
        assertNotSame(loadList, preLoadList);
        assertNotSame(loadList, deleteList);
        assertNotSame(loadList, preLinkList);
        assertNotSame(unloadList, cloneList);
        assertNotSame(unloadList, preLoadList);
        assertNotSame(unloadList, deleteList);
        assertNotSame(unloadList, preLinkList);
        assertNotSame(cloneList, preLoadList);
        assertNotSame(cloneList, deleteList);
        assertNotSame(cloneList, preLinkList);
        assertNotSame(preLoadList, deleteList);
        assertNotSame(preLoadList, preLinkList);
        assertNotSame(deleteList, preLinkList);
    }
}