package dev.franco.svcrejoin.listener;

import dev.franco.svcrejoin.service.VoiceSessionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

/** Arms the join hint only after the voice mod registers, and clears per-player state on quit. */
public final class PlayerSessionListener implements Listener {

    private final VoiceSessionService service;

    public PlayerSessionListener(VoiceSessionService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.onPlayerJoin(event.getPlayer());
    }

    /** The voice mod registers {@code voicechat:secret}. Players without it never get a join check. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChannel(PlayerRegisterChannelEvent event) {
        service.onPluginChannel(event.getPlayer(), event.getChannel());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
