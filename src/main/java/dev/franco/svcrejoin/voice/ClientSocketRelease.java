package dev.franco.svcrejoin.voice;

import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Puts the voice client back in the state it has on a normal join: no UDP socket.
 *
 * <p>A secret received while a socket is already open makes the client close that socket and open
 * another one in the same call. The old authentication thread can still be inside {@code send()}
 * when the socket closes; the failure calls disconnect and kills the replacement socket, so the
 * server logs "Successfully authenticated" and never "Successfully validated connection".</p>
 *
 * <p>A secret whose voice host cannot be resolved still closes the current socket (the HUD updates
 * immediately) and then {@code connect()} throws before a new socket exists. The next real secret
 * is handled exactly like the one the client asks for when joining.</p>
 */
final class ClientSocketRelease {

    /**
     * {@code .invalid} is a reserved TLD, so {@code InetAddress.getByName} fails immediately and
     * {@code URI.getHost()} still returns the name (an invalid IP literal would be parsed as empty
     * and the client would connect to the real server instead).
     */
    private static final String IDLE_HOST = "voicechat-rejoin.invalid";

    private final MethodHandle newSecret;
    private final MethodHandle serverConfig;
    private final MethodHandle getPort;
    private final Constructor<?> bukkitPacket;
    private final Constructor<?> paperPacket;
    private final Field voiceHost;
    private final MethodHandle bukkitSend;
    private final MethodHandle paperSend;
    private final MethodHandle toServerPlayer;

    private ClientSocketRelease(MethodHandle newSecret, MethodHandle serverConfig, MethodHandle getPort,
                                Constructor<?> bukkitPacket, Constructor<?> paperPacket, Field voiceHost,
                                MethodHandle bukkitSend, MethodHandle paperSend, MethodHandle toServerPlayer) {
        this.newSecret = newSecret;
        this.serverConfig = serverConfig;
        this.getPort = getPort;
        this.bukkitPacket = bukkitPacket;
        this.paperPacket = paperPacket;
        this.voiceHost = voiceHost;
        this.bukkitSend = bukkitSend;
        this.paperSend = paperSend;
        this.toServerPlayer = toServerPlayer;
    }

    /**
     * @return {@code null} when this SVC build does not expose the pieces needed to send the idle secret
     */
    static ClientSocketRelease tryCreate(ClassLoader loader, Class<?> serverClass) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> voicechat = Class.forName("de.maxhenkel.voicechat.Voicechat", false, loader);
            Class<?> secret = Class.forName("de.maxhenkel.voicechat.voice.common.Secret", false, loader);
            Class<?> config = Class.forName("de.maxhenkel.voicechat.config.ServerConfig", false, loader);
            Class<?> packet = Class.forName("de.maxhenkel.voicechat.net.SecretPacket", false, loader);
            Class<?> packetType = Class.forName("de.maxhenkel.voicechat.net.Packet", false, loader);

            MethodHandle newSecret = lookup.findStatic(secret, "generateNewRandomSecret", MethodType.methodType(secret))
                    .asType(MethodType.methodType(Object.class));
            MethodHandle serverConfig = lookup.findStaticGetter(voicechat, "SERVER_CONFIG", config)
                    .asType(MethodType.methodType(Object.class));
            MethodHandle getPort = lookup.findVirtual(serverClass, "getPort", MethodType.methodType(int.class))
                    .asType(MethodType.methodType(int.class, Object.class));

            Constructor<?> bukkitPacket = findConstructor(packet, Player.class, secret, int.class, config);
            Field voiceHost = null;
            MethodHandle bukkitSend = null;
            if (bukkitPacket != null) {
                voiceHost = packet.getDeclaredField("voiceHost");
                voiceHost.setAccessible(true);
                Class<?> net = Class.forName("de.maxhenkel.voicechat.net.NetManager", false, loader);
                bukkitSend = lookup.findStatic(net, "sendToClient", MethodType.methodType(void.class, Player.class, packetType))
                        .asType(MethodType.methodType(void.class, Player.class, Object.class));
            }

            Constructor<?> paperPacket = null;
            MethodHandle paperSend = null;
            MethodHandle toServerPlayer = null;
            if (bukkitPacket == null) {
                Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", false, loader);
                paperPacket = packet.getConstructor(serverPlayer, secret, int.class, config, String.class);
                Class<?> utils = Class.forName("de.maxhenkel.voicechat.BukkitUtils", false, loader);
                toServerPlayer = lookup.findStatic(utils, "getPlayer", MethodType.methodType(serverPlayer, Player.class))
                        .asType(MethodType.methodType(Object.class, Player.class));
                Object net = netManager(loader);
                paperSend = lookup.findVirtual(net.getClass(), "sendToClient",
                                MethodType.methodType(void.class, packetType, serverPlayer))
                        .bindTo(net)
                        .asType(MethodType.methodType(void.class, Object.class, Object.class));
            }

            if (bukkitPacket == null && paperPacket == null) {
                return null;
            }
            return new ClientSocketRelease(newSecret, serverConfig, getPort, bukkitPacket, paperPacket,
                    voiceHost, bukkitSend, paperSend, toServerPlayer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Sends a secret that drops the client's socket and leaves it idle. The secret is not registered. */
    void release(Object server, Player player) throws Throwable {
        int port = (int) getPort.invokeExact(server);
        Object secret = (Object) newSecret.invokeExact();
        Object config = (Object) serverConfig.invokeExact();
        if (bukkitPacket != null) {
            Object packet = bukkitPacket.newInstance(player, secret, port, config);
            voiceHost.set(packet, IDLE_HOST);
            bukkitSend.invokeExact(player, packet);
            return;
        }
        Object nmsPlayer = (Object) toServerPlayer.invokeExact(player);
        Object packet = paperPacket.newInstance(nmsPlayer, secret, port, config, IDLE_HOST);
        paperSend.invokeExact(packet, nmsPlayer);
    }

    private static Constructor<?> findConstructor(Class<?> type, Class<?>... params) {
        try {
            return type.getConstructor(params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Paper's {@code NetManager.sendToClient} is an instance method, unlike the bukkit static one. */
    private static Object netManager(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> compat = Class.forName("de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager", false, loader);
        Object instance = compat.getField("INSTANCE").get(null);
        return compat.getMethod("getNetManager").invoke(instance);
    }
}
