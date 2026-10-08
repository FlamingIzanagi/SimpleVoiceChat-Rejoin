package dev.franco.svcrejoin.command;

import dev.franco.svcrejoin.Permissions;
import dev.franco.svcrejoin.config.Messages;
import dev.franco.svcrejoin.service.VoiceSessionService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bukkit build of SVC: {@code /voicechat} is a {@link PluginCommand}. We wrap its executor and tab
 * completer, handle {@code rejoin}/{@code disconnect} ourselves and delegate everything else untouched.
 * Senders without permission fall through to SVC, so the subcommands stay invisible to them.
 */
final class VoicechatExecutorHook implements CommandExecutor, TabCompleter {

    private final PluginCommand command;
    private final VoiceSessionService service;
    private final CommandExecutor originalExecutor;
    private final @Nullable TabCompleter originalCompleter;
    private final Map<String, VoiceSubcommand> subcommands;

    private VoicechatExecutorHook(PluginCommand command, VoiceSessionService service, Map<String, VoiceSubcommand> subcommands) {
        this.command = command;
        this.service = service;
        this.originalExecutor = command.getExecutor();
        this.originalCompleter = command.getTabCompleter();
        this.subcommands = subcommands;
    }

    static VoicechatExecutorHook install(PluginCommand command, VoiceSessionService service, Map<String, VoiceSubcommand> subcommands) {
        VoicechatExecutorHook hook = new VoicechatExecutorHook(command, service, subcommands);
        command.setExecutor(hook);
        command.setTabCompleter(hook);
        return hook;
    }

    /** Puts SVC's original handlers back, unless another plugin replaced ours in the meantime. */
    void uninstall() {
        if (command.getExecutor() == this) {
            command.setExecutor(originalExecutor);
        }
        if (command.getTabCompleter() == this) {
            command.setTabCompleter(originalCompleter);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && sender.hasPermission(Permissions.USE)) {
            VoiceSubcommand subcommand = subcommands.get(args[0].toLowerCase(Locale.ROOT));
            if (subcommand != null) {
                subcommand.executeBukkit(sender, Arrays.copyOfRange(args, 1, args.length));
                return true;
            }
        }
        boolean handled = originalExecutor.onCommand(sender, cmd, label, args);
        if ((args.length == 0 || args[0].equalsIgnoreCase("help")) && sender.hasPermission(Permissions.USE)) {
            appendHelp(sender);
        }
        return handled;
    }

    /** Adds our lines after SVC's own {@code /voicechat [help]} listing, in the same style. */
    private void appendHelp(CommandSender sender) {
        Messages messages = service.messages();
        TagResolver args = Placeholder.parsed("args",
                sender.hasPermission(Permissions.OTHERS) ? messages.raw("help-target-argument") : "");
        for (String name : subcommands.keySet()) {
            messages.sendWithoutPrefix(sender, "help-" + name, args);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        List<String> original = originalCompleter != null ? originalCompleter.onTabComplete(sender, cmd, label, args) : null;
        if (!sender.hasPermission(Permissions.USE)) {
            return original;
        }

        if (args.length == 1) {
            List<String> result = original != null ? new ArrayList<>(original) : new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String name : subcommands.keySet()) {
                if (name.startsWith(prefix) && !result.contains(name)) {
                    result.add(name);
                }
            }
            return result;
        }

        if (args.length == 2 && subcommands.containsKey(args[0].toLowerCase(Locale.ROOT))
                && sender.hasPermission(Permissions.OTHERS)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            Player viewer = sender instanceof Player player ? player : null;
            for (Player online : Bukkit.getOnlinePlayers()) {
                if ((viewer == null || viewer.canSee(online)) && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        return original;
    }
}
