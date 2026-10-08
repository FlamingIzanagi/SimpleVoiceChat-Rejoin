package dev.franco.svcrejoin.command;

import dev.franco.svcrejoin.service.VoiceSessionService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /voicechat disconnect [player]}: ends the player's voice session server-side. The player
 * stays disconnected (no auto-rejoin) until {@code /voicechat rejoin} or a relog.
 */
final class DisconnectCommand extends VoiceSubcommand {

    DisconnectCommand(VoiceSessionService service) {
        super(service, "disconnect");
    }

    @Override
    protected void execute(CommandSender sender, Player target) {
        service.requestDisconnect(sender, target);
    }
}
