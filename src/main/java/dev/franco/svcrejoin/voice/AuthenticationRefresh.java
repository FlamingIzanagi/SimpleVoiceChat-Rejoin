package dev.franco.svcrejoin.voice;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.Map;
import java.util.UUID;

/**
 * SVC stores the UDP address of the first authenticate packet and keeps sending the ack there.
 * A later packet from the real socket is accepted, but the ack still goes to the first address,
 * so the client never logs "Server acknowledged authentication" and never sends the connection
 * check. Forgetting that half-open session (and not the secret) makes the next authenticate
 * packet — the client sends one every second — the one SVC answers.
 */
final class AuthenticationRefresh {

    private final Field uncheckedConnections;
    private final MethodHandle getAddress;

    private AuthenticationRefresh(Field uncheckedConnections, MethodHandle getAddress) {
        this.uncheckedConnections = uncheckedConnections;
        this.getAddress = getAddress;
    }

    /** @return {@code null} when this SVC build does not expose the half-open sessions */
    static AuthenticationRefresh tryCreate(ClassLoader loader, Class<?> serverClass) {
        try {
            Field unchecked = serverClass.getDeclaredField("unCheckedConnections");
            unchecked.setAccessible(true);
            Class<?> connection = Class.forName("de.maxhenkel.voicechat.voice.server.ClientConnection", false, loader);
            MethodHandle getAddress = MethodHandles.publicLookup()
                    .findVirtual(connection, "getAddress", MethodType.methodType(SocketAddress.class))
                    .asType(MethodType.methodType(SocketAddress.class, Object.class));
            return new AuthenticationRefresh(unchecked, getAddress);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Drops the half-open session so the next authenticate packet is stored with its own address.
     * The secret stays, otherwise that packet could not be decrypted.
     *
     * @return the address that was being answered, or {@code null} if the player is not half-open
     */
    String forgetPending(Object server, UUID playerId) throws Throwable {
        Object raw = uncheckedConnections.get(server);
        if (!(raw instanceof Map<?, ?> pending)) {
            return null;
        }
        Object connection = pending.get(playerId);
        if (connection == null) {
            return null;
        }
        SocketAddress address = (SocketAddress) getAddress.invokeExact(connection);
        pending.remove(playerId);
        return String.valueOf(address);
    }
}
