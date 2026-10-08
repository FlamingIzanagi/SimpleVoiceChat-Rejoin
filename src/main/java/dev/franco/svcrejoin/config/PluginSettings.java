package dev.franco.svcrejoin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of {@code config.yml}. Read once at startup so the hot paths
 * (commands, voice events) never touch the YAML tree.
 */
public record PluginSettings(
        long rejoinCooldownMillis,
        long reconnectDelayMillis,
        long rejoinTimeoutSeconds,
        boolean requireCompatibleClient,
        boolean notifyRejoinTarget,
        boolean notifyDisconnectTarget,
        boolean autoRejoinEnabled,
        long joinCheckDelayTicks,
        long lostCheckDelayTicks,
        int autoRejoinMaxAttempts,
        boolean rejoinQueueEnabled,
        int maxConcurrentRejoins,
        boolean hintOnJoin,
        boolean hintOnConnectionLost,
        boolean troubleshootingEnabled,
        boolean debug
) {

    private static final long TICKS_PER_SECOND = 20L;

    public static PluginSettings load(FileConfiguration config) {
        return new PluginSettings(
                Math.max(0L, config.getLong("rejoin.cooldown-seconds", 10L)) * 1000L,
                Math.max(0L, config.getLong("rejoin.reconnect-delay-ms", 250L)),
                Math.max(1L, config.getLong("rejoin.timeout-seconds", 15L)),
                config.getBoolean("rejoin.require-compatible-client", true),
                config.getBoolean("rejoin.notify-target", true),
                config.getBoolean("disconnect.notify-target", true),
                config.getBoolean("auto-rejoin.enabled", true),
                Math.max(1L, config.getLong("auto-rejoin.join-check-delay-seconds", 20L)) * TICKS_PER_SECOND,
                Math.max(1L, config.getLong("auto-rejoin.lost-check-delay-seconds", 15L)) * TICKS_PER_SECOND,
                Math.max(0, config.getInt("auto-rejoin.max-attempts", 2)),
                config.getBoolean("rejoin.queue.enabled", true),
                Math.max(1, config.getInt("rejoin.queue.max-concurrent", 5)),
                config.getBoolean("hints.on-join", true),
                config.getBoolean("hints.on-connection-lost", true),
                config.getBoolean("troubleshooting.enabled", true),
                config.getBoolean("debug", false)
        );
    }
}
