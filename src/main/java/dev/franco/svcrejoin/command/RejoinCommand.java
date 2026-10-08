package dev.franco.svcrejoin.command;

import dev.franco.svcrejoin.service.VoiceSessionService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /voicechat rejoin [player]}: drops the player's current voice session and makes the client
 * authenticate again with a fresh secret, without leaving the Minecraft server.
 */
final class RejoinCommand extends VoiceSubcommand {

    RejoinCommand(VoiceSessionService service) {
        super(service, "rejoin");
    }

    @Override
    protected void execute(CommandSender sender, Player target) {
        service.requestRejoin(sender, target);
    }
}
