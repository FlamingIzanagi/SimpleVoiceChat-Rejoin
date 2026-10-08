package dev.franco.svcrejoin.fabric;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * Client addon for Simple Voice Chat. The server plugin still clears the session and sends the
 * new secret; this only makes the replacement UDP socket bind the local port that already connected.
 */
public final class RejoinVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "svc_rejoin";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientVoicechatInitializationEvent.class, event ->
                event.setSocketImplementation(new ReusePortVoicechatSocket()));
        registration.registerEvent(ClientVoicechatConnectionEvent.class, event -> {
            if (event.isConnected()) {
                ReusePortVoicechatSocket.markCurrentProven();
            }
        });
    }
}
