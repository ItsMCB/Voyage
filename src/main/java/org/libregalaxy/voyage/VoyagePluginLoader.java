package org.libregalaxy.voyage;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

class VoyagePluginLoader implements PluginLoader {

    @Override
    public void classloader(final PluginClasspathBuilder builder) {
        final MavenLibraryResolver maven = new MavenLibraryResolver();
        // Configure Paper's default mirror (using direct central is against their TOS)
        maven.addRepository(new RemoteRepository.Builder("maven-central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        // Runtime dependencies
        maven.addDependency(new Dependency(new DefaultArtifact("net.kyori:adventure-nbt:5.1.1"), null));

        // High-performance embedded SQLite and connection pooling
        maven.addDependency(new Dependency(new DefaultArtifact("org.xerial:sqlite-jdbc:3.49.1.0"), null));
        maven.addDependency(new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:6.2.1"), null));

        // Remember: DO NOT add plugin dependencies here; they go in the paper-plugin.yml server dependencies section.
        builder.addLibrary(maven);
    }
}