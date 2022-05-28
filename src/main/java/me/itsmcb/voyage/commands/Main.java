package me.itsmcb.voyage.commands;

import me.itsmcb.voyage.Voyage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class Main implements CommandExecutor {

    private me.itsmcb.voyage.Voyage instance;

    public Main(Voyage instance) {
        this.instance = instance;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // TODO add config reload subcommand, help subcommand, etc.
        return false;
    }
}
