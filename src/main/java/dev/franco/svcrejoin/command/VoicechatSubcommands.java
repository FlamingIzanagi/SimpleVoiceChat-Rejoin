package dev.franco.svcrejoin.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.franco.svcrejoin.service.VoiceSessionService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Attaches {@code rejoin} and {@code disconnect} under Simple Voice Chat's existing {@code /voicechat},
 * adapting to how the installed SVC build registers that command:
 *
 * <ul>
 *     <li><b>Bukkit build</b>: {@code /voicechat} is a {@link PluginCommand} from SVC's {@code plugin.yml}.
 *     We wrap its executor/tab completer ({@link VoicechatExecutorHook}).</li>
 *     <li><b>Paper module build</b>: {@code /voicechat} is put straight into the vanilla Brigadier
 *     dispatcher. {@link Commands#register} would <em>replace</em> that node (the main label always
 *     overrides), so instead an executor-less {@code voicechat} literal is added through the dispatcher
 *     root, which hits Brigadier's merge path and only appends our children. This is the addon use case
 *     documented on {@link Commands#getDispatcher()}. The handler re-runs on every reload.</li>
 * </ul>
 */
public final class VoicechatSubcommands {

    private static final String ROOT_LITERAL = "voicechat";

    private final @Nullable VoicechatExecutorHook executorHook;

    private VoicechatSubcommands(@Nullable VoicechatExecutorHook executorHook) {
        this.executorHook = executorHook;
    }

    public static VoicechatSubcommands register(JavaPlugin plugin, Plugin voicechatPlugin, VoiceSessionService service) {
        Map<String, VoiceSubcommand> subcommands = new LinkedHashMap<>();
        for (VoiceSubcommand subcommand : new VoiceSubcommand[]{new RejoinCommand(service), new DisconnectCommand(service)}) {
            subcommands.put(subcommand.name(), subcommand);
        }

        PluginCommand bukkitCommand = voicechatPlugin instanceof JavaPlugin javaPlugin ? javaPlugin.getCommand(ROOT_LITERAL) : null;
        if (bukkitCommand != null) {
            return new VoicechatSubcommands(VoicechatExecutorHook.install(bukkitCommand, service, subcommands));
        }

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            var builder = Commands.literal(ROOT_LITERAL);
            subcommands.values().forEach(subcommand -> builder.then(subcommand.build()));
            LiteralCommandNode<CommandSourceStack> node = builder.build();

            if (registrar.getDispatcher().getRoot().getChild(ROOT_LITERAL) == null) {
                plugin.getLogger().warning("Simple Voice Chat's /" + ROOT_LITERAL
                        + " command was not found; registering a standalone one with only rejoin/disconnect.");
            }
            registrar.getDispatcher().getRoot().addChild(node);
        });
        return new VoicechatSubcommands(null);
    }

    public String strategy() {
        return executorHook != null ? "bukkit-command" : "brigadier";
    }

    public void unregister() {
        if (executorHook != null) {
            executorHook.uninstall();
        }
    }
}
