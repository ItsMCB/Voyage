package me.itsmcb.voyage.api;

import me.itsmcb.vexelcore.common.api.utils.FileUtils;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class VoyageWorldCreator {

    public static VoyageWorld copyFromSource(File sourceWorldFile, String nameOfNewWorld) throws IOException {
        List<Path> ignoredFiles = List.of(Path.of("uid.dat"),Path.of("session.lock"));
        FileUtils.copyDirectory(sourceWorldFile.toPath(), new File(Bukkit.getWorldContainer()+File.separator+nameOfNewWorld).toPath(),ignoredFiles);
        return new VoyageWorld(nameOfNewWorld);
    }

}
