package dev.franco.svcrejoin.voice;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.UUID;

/**
 * Access to the Simple Voice Chat server internals that its public API does not expose.
 *
 * <p>The official API ({@code VoicechatConnection#setConnected}) only fakes the state of players
 * <em>without</em> the mod, so a real reconnect has to drive SVC's own server objects. A normal join
 * works because the client has no UDP socket when the secret arrives. Rejoin reproduces that: clear
 * the server session, make the client drop its socket and stay idle, then send a real secret. SVC's
 * own packet thread then logs the authentication, the validation and the connection.</p>
 *
 * <p>Two SVC server builds exist and both are supported:</p>
 * <ul>
 *     <li><b>Bukkit build</b> ({@code voicechat-bukkit-x.y.z.jar}, the one published for Paper/Spigot):
 *     internals take {@code org.bukkit.entity.Player}.</li>
 *     <li><b>Paper module build</b> (shared "common" code): internals take NMS {@code ServerPlayer},
 *     converted through SVC's {@code BukkitUtils}.</li>
 * </ul>
 *
 * <p>Every handle is resolved once at startup and adapted to an erased signature so calls go through
 * {@link MethodHandle#invokeExact}, which the JIT inlines like a direct call. SVC itself invokes
 * these methods from its packet-processing thread, so they are safe to call off the main thread.</p>
 */
public final class VoicechatBridge {

    private static final String VOICECHAT = "de.maxhenkel.voicechat.Voicechat";
    private static final String SERVER_VOICE_EVENTS = "de.maxhenkel.voicechat.voice.server.ServerVoiceEvents";
    private static final String SERVER = "de.maxhenkel.voicechat.voice.server.Server";
    private static final String PLAYER_STATE_MANAGER = "de.maxhenkel.voicechat.voice.server.PlayerStateManager";
    private static final String BUKKIT_UTILS = "de.maxhenkel.voicechat.BukkitUtils";
    private static final String SERVER_PLAYER = "net.minecraft.server.level.ServerPlayer";

    /** {@code () -> Voicechat.SERVER} (ServerVoiceEvents, nullable before init). */
    private final MethodHandle serverEvents;
    /** {@code (ServerVoiceEvents) -> Server} (nullable while the voice server is stopped). */
    private final MethodHandle getServer;
    /** {@code (ServerVoiceEvents, UUID) -> boolean}. */
    private final MethodHandle isCompatible;
    /** {@code (ServerVoiceEvents, org.bukkit.entity.Player) -> void}: generates and sends a new secret. */
    private final MethodHandle initializePlayerConnection;
    /** {@code (Server) -> boolean}. */
    private final MethodHandle isClosed;
    /** {@code (Server) -> Map<UUID, ClientConnection>} (validated sessions only, concurrent map). */
    private final MethodHandle getConnections;
    /** {@code (Server, UUID) -> void}: removes connection, pending connection and secret. */
    private final MethodHandle disconnectClient;
    /** {@code (Server, UUID) -> void}: marks the player disconnected for every other client. */
    private final MethodHandle broadcastDisconnected;
    private final String layout;
    /** {@code null} when this SVC build cannot be told to drop the client socket without reconnecting. */
    private final ClientSocketRelease clientRelease;
    /** {@code null} when half-open sessions cannot be dropped without deleting the secret. */
    private final AuthenticationRefresh authenticationRefresh;

    private VoicechatBridge(MethodHandle serverEvents, MethodHandle getServer, MethodHandle isCompatible,
                            MethodHandle initializePlayerConnection, MethodHandle isClosed,
                            MethodHandle getConnections, MethodHandle disconnectClient,
                            MethodHandle broadcastDisconnected, String layout, ClientSocketRelease clientRelease,
                            AuthenticationRefresh authenticationRefresh) {
        this.serverEvents = serverEvents;
        this.getServer = getServer;
        this.isCompatible = isCompatible;
        this.initializePlayerConnection = initializePlayerConnection;
        this.isClosed = isClosed;
        this.getConnections = getConnections;
        this.disconnectClient = disconnectClient;
        this.broadcastDisconnected = broadcastDisconnected;
        this.layout = layout;
        this.clientRelease = clientRelease;
        this.authenticationRefresh = authenticationRefresh;
    }

    /**
     * Resolves all handles against the classloader of the Simple Voice Chat plugin.
     *
     * @throws ReflectiveOperationException if the installed SVC version has an unknown internal layout
     */
    public static VoicechatBridge create(Plugin voicechatPlugin) throws ReflectiveOperationException {
        ClassLoader loader = voicechatPlugin.getClass().getClassLoader();
        Class<?> voicechat = Class.forName(VOICECHAT, false, loader);
        Class<?> events = Class.forName(SERVER_VOICE_EVENTS, false, loader);
        Class<?> server = Class.forName(SERVER, false, loader);
        Class<?> stateManager = Class.forName(PLAYER_STATE_MANAGER, false, loader);

        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        MethodHandle serverEvents = lookup.findStaticGetter(voicechat, "SERVER", events)
                .asType(MethodType.methodType(Object.class));
        MethodHandle getServer = lookup.findVirtual(events, "getServer", MethodType.methodType(server))
                .asType(MethodType.methodType(Object.class, Object.class));
        MethodHandle isCompatible = lookup.findVirtual(events, "isCompatible", MethodType.methodType(boolean.class, UUID.class))
                .asType(MethodType.methodType(boolean.class, Object.class, UUID.class));
        MethodHandle isClosed = lookup.findVirtual(server, "isClosed", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        MethodHandle getConnections = lookup.findVirtual(server, "getConnections", MethodType.methodType(Map.class))
                .asType(MethodType.methodType(Map.class, Object.class));
        MethodHandle disconnectClient = lookup.findVirtual(server, "disconnectClient", MethodType.methodType(void.class, UUID.class))
                .asType(MethodType.methodType(void.class, Object.class, UUID.class));

        // server.getPlayerStateManager().onPlayerVoicechatDisconnect(uuid), fused into one handle.
        MethodHandle getStateManager = lookup.findVirtual(server, "getPlayerStateManager", MethodType.methodType(stateManager));
        MethodHandle onDisconnect = lookup.findVirtual(stateManager, "onPlayerVoicechatDisconnect", MethodType.methodType(void.class, UUID.class));
        MethodHandle broadcastDisconnected = MethodHandles.filterArguments(onDisconnect, 0, getStateManager)
                .asType(MethodType.methodType(void.class, Object.class, UUID.class));

        MethodHandle initialize;
        String layout;
        try {
            initialize = lookup.findVirtual(events, "initializePlayerConnection", MethodType.methodType(void.class, Player.class));
            layout = "bukkit";
        } catch (NoSuchMethodException bukkitLayoutMissing) {
            Class<?> serverPlayer = Class.forName(SERVER_PLAYER, false, loader);
            Class<?> bukkitUtils = Class.forName(BUKKIT_UTILS, false, loader);
            MethodHandle toServerPlayer = lookup.findStatic(bukkitUtils, "getPlayer", MethodType.methodType(serverPlayer, Player.class));
            MethodHandle nmsInitialize = lookup.findVirtual(events, "initializePlayerConnection", MethodType.methodType(void.class, serverPlayer));
            initialize = MethodHandles.filterArguments(nmsInitialize, 1, toServerPlayer);
            layout = "paper";
        }
        initialize = initialize.asType(MethodType.methodType(void.class, Object.class, Player.class));

        return new VoicechatBridge(serverEvents, getServer, isCompatible, initialize, isClosed,
                getConnections, disconnectClient, broadcastDisconnected, layout,
                ClientSocketRelease.tryCreate(loader, server),
                AuthenticationRefresh.tryCreate(loader, server));
    }

    /** @return whether the client socket can be dropped without starting a new handshake. */
    public boolean canReleaseClient() {
        return clientRelease != null;
    }

    /** @return {@code "bukkit"} or {@code "paper"}, depending on the detected SVC build. */
    public String layout() {
        return layout;
    }

    /** @return whether the SVC UDP server is up and accepting players. */
    public boolean isVoiceServerRunning() {
        Object server = currentServer();
        if (server == null) {
            return false;
        }
        try {
            return !(boolean) isClosed.invokeExact(server);
        } catch (Throwable t) {
            throw new BridgeException("Server#isClosed", t);
        }
    }

    /** @return whether the player's client requested a secret with a compatible mod version. */
    public boolean isCompatible(UUID playerId) {
        Object events = currentEvents();
        if (events == null) {
            return false;
        }
        try {
            return (boolean) isCompatible.invokeExact(events, playerId);
        } catch (Throwable t) {
            throw new BridgeException("ServerVoiceEvents#isCompatible", t);
        }
    }

    /**
     * Makes the client close its UDP socket and stay idle, without registering a secret.
     *
     * @return {@code false} when the voice server is down or this SVC build cannot send the idle secret
     */
    public boolean releaseClient(Player player) {
        if (clientRelease == null) {
            return false;
        }
        Object server = currentServer();
        if (server == null) {
            return false;
        }
        try {
            clientRelease.release(server, player);
            return true;
        } catch (Throwable t) {
            throw new BridgeException("idle secret", t);
        }
    }

    /**
     * Forgets the half-open UDP session left by an authenticate packet that never completed.
     * The secret is kept, so the client's next authenticate packet is decrypted and stored
     * with the address it was actually sent from.
     *
     * @return that stuck address, or {@code null} when the player is not waiting for the ack
     */
    public @Nullable String forgetPendingAuthentication(UUID playerId) {
        if (authenticationRefresh == null) {
            return null;
        }
        Object server = currentServer();
        if (server == null) {
            return null;
        }
        try {
            return authenticationRefresh.forgetPending(server, playerId);
        } catch (Throwable t) {
            throw new BridgeException("unCheckedConnections", t);
        }
    }

    /** @return whether a fully validated UDP session exists for the player. */
    public boolean hasActiveConnection(UUID playerId) {
        Object server = currentServer();
        if (server == null) {
            return false;
        }
        try {
            return ((Map<?, ?>) getConnections.invokeExact(server)).containsKey(playerId);
        } catch (Throwable t) {
            throw new BridgeException("Server#getConnections", t);
        }
    }

    /**
     * Tears down the player's voice session server-side and broadcasts the disconnected state.
     * The client stops receiving audio immediately; its own UI notices after its keep-alive timeout.
     *
     * @return {@code false} if the voice server is not running
     */
    public boolean disconnect(UUID playerId) {
        Object server = currentServer();
        if (server == null) {
            return false;
        }
        try {
            disconnectClient.invokeExact(server, playerId);
            broadcastDisconnected.invokeExact(server, playerId);
            return true;
        } catch (Throwable t) {
            throw new BridgeException("Server#disconnectClient", t);
        }
    }

    /**
     * Sends a fresh secret to the client, which makes it drop any old session and authenticate again.
     * Must be preceded by {@link #disconnect(UUID)}, otherwise SVC ignores the request because a
     * secret already exists.
     *
     * @return {@code false} if the voice server is not running
     */
    public boolean initializeConnection(Player player) {
        Object events = currentEvents();
        if (events == null || currentServer() == null) {
            return false;
        }
        try {
            initializePlayerConnection.invokeExact(events, player);
            return true;
        } catch (Throwable t) {
            throw new BridgeException("ServerVoiceEvents#initializePlayerConnection", t);
        }
    }

    private Object currentEvents() {
        try {
            return (Object) serverEvents.invokeExact();
        } catch (Throwable t) {
            throw new BridgeException("Voicechat.SERVER", t);
        }
    }

    private Object currentServer() {
        Object events = currentEvents();
        if (events == null) {
            return null;
        }
        try {
            return (Object) getServer.invokeExact(events);
        } catch (Throwable t) {
            throw new BridgeException("ServerVoiceEvents#getServer", t);
        }
    }

    /** Unchecked wrapper for failures inside Simple Voice Chat internals. */
    public static final class BridgeException extends RuntimeException {
        BridgeException(String target, Throwable cause) {
            super("Simple Voice Chat internal call failed: " + target, cause);
        }
    }
}
