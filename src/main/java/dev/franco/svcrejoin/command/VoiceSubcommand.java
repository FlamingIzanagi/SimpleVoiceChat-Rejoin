package dev.franco.svcrejoin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.franco.svcrejoin.Permissions;
import dev.franco.svcrejoin.config.Messages;
import dev.franco.svcrejoin.service.VoiceSessionService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Shared shape of {@code /voicechat <name> [player]}:
 * no argument targets the executor (requires {@link Permissions#USE}),
 * a player argument targets someone else (requires {@link Permissions#OTHERS}).
 *
 * <p>Exposed both as a Brigadier node (Paper module build of SVC) and as a classic
 * argument handler (Bukkit build of SVC, whose {@code /voicechat} is a {@code PluginCommand}).</p>
 */
abstract class VoiceSubcommand {

    private static final String TARGET_ARGUMENT = "player";

    protected final VoiceSessionService service;
    private final String name;

    VoiceSubcommand(VoiceSessionService service, String name) {
        this.service = service;
        this.name = name;
    }

    /** Runs on the main thread; implementations must hand any heavy work to the service. */
    protected abstract void execute(CommandSender sender, Player target);

    final String name() {
        return name;
    }

    // ---------------------------------------------------------------- Bukkit-style dispatch

    /** @param args the arguments after the subcommand literal */
    final void executeBukkit(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                execute(sender, player);
            } else {
                service.messages().send(sender, "console-needs-target", Placeholder.unparsed("action", name));
            }
            return;
        }
        if (!sender.hasPermission(Permissions.OTHERS)) {
            service.messages().send(sender, "no-permission");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            service.messages().send(sender, "player-not-found", Messages.player(args[0]));
            return;
        }
        execute(sender, target);
    }

    // ---------------------------------------------------------------- Brigadier dispatch

    final LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal(name)
                .requires(source -> source.getSender().hasPermission(Permissions.USE))
                .executes(this::executeSelf)
                .then(Commands.argument(TARGET_ARGUMENT, ArgumentTypes.player())
                        .requires(source -> source.getSender().hasPermission(Permissions.OTHERS))
                        .executes(this::executeOther));
    }

    private int executeSelf(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        // Honour "/execute as <player> run voicechat ..." by preferring the executor.
        Player target = source.getExecutor() instanceof Player executor ? executor
                : source.getSender() instanceof Player sender ? sender
                : null;
        if (target == null) {
            service.messages().send(source.getSender(), "console-needs-target", Placeholder.unparsed("action", name));
            return 0;
        }
        execute(source.getSender(), target);
        return Command.SINGLE_SUCCESS;
    }

    private int executeOther(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = context.getArgument(TARGET_ARGUMENT, PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(context.getSource()).getFirst();
        execute(context.getSource().getSender(), target);
        return Command.SINGLE_SUCCESS;
    }
}
