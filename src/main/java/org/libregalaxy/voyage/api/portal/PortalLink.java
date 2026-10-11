package org.libregalaxy.voyage.api.portal;

import org.bukkit.NamespacedKey;

public record PortalLink(
        NamespacedKey targetWorld,
        CoordinateStrategy coordinateStrategy,
        PortalGenerator generator
) {}