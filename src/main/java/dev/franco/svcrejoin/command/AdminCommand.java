package dev.franco.svcrejoin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.franco.svcrejoin.Permissions;
import dev.franco.svcrejoin.SvcRejoinPlugin;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.util.List;

/** {@code /svcrejoin reload}: our own root command, so it is registered normally (no SVC conflict). */
public final class AdminCommand {

    private AdminCommand() {
    }

    public static void register(SvcRejoinPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = Commands.literal("svcrejoin")
                    .requires(source -> source.getSender().hasPermission(Permissions.RELOAD))
                    .executes(context -> {
                        plugin.messages().send(context.getSource().getSender(), "admin-usage");
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.literal("reload")
                            .executes(context -> {
                                plugin.reloadSettings(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }))
                    .build();
            event.registrar().register(node, "SVCRejoin administration", List.of());
        });
    }
}
