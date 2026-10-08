package dev.franco.svcrejoin.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;

import java.util.UUID;

/**
 * Official Simple Voice Chat API integration, used to observe connection changes.
 *
 * <p>SVC offers no way to unregister an API plugin or its listeners, so instead of letting the
 * registered lambdas keep the whole plugin graph alive after a disable/reload, they only reference
 * a {@code volatile} listener that {@link #detach()} clears. A detached instance is a no-op shell.</p>
 *
 * <p>SVC fires these events on its packet-processing thread: listeners must be thread-safe and cheap.</p>
 */
public final class RejoinVoicechatPlugin implements VoicechatPlugin {

    /** Receives voice connection changes; implementations must be thread-safe. */
    public interface ConnectionListener {
        void onVoiceConnected(UUID playerId);

        void onVoiceDisconnected(UUID playerId);
    }

    private volatile ConnectionListener listener;

    public RejoinVoicechatPlugin(ConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public String getPluginId() {
        return "svc_rejoin";
    }

    @Override
    public void initialize(VoicechatApi api) {
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(PlayerConnectedEvent.class, event -> {
            ConnectionListener current = listener;
            if (current != null) {
                current.onVoiceConnected(event.getConnection().getPlayer().getUuid());
            }
        });
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            ConnectionListener current = listener;
            if (current != null) {
                current.onVoiceDisconnected(event.getPlayerUuid());
            }
        });
    }

    public void detach() {
        listener = null;
    }
}
