package dev.franco.svcrejoin.service;

import dev.franco.svcrejoin.Permissions;
import dev.franco.svcrejoin.config.Messages;
import dev.franco.svcrejoin.config.PluginSettings;
import dev.franco.svcrejoin.voice.RejoinVoicechatPlugin;
import dev.franco.svcrejoin.voice.VoicechatBridge;
import dev.franco.svcrejoin.voice.VoicechatBridge.BridgeException;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Core logic behind {@code /voicechat rejoin} and {@code /voicechat disconnect}, plus the
 * connection watchdog (auto-rejoin and clickable hints).
 *
 * <h2>Threading</h2>
 * <ul>
 *     <li>Commands and the watchdog run on the main/entity thread and only do O(1) checks.</li>
 *     <li>Every call that mutates SVC state or sends voice packets runs on Paper's async scheduler.</li>
 *     <li>{@link #onVoiceConnected}/{@link #onVoiceDisconnected} arrive on SVC's packet thread.</li>
 *     <li>The automatic-rejoin queue is guarded by {@link #queueLock}.</li>
 * </ul>
 * All shared state therefore lives in concurrent collections, except the queue.
 *
 * <h2>Memory</h2>
 * Every per-player entry is removed on quit ({@link #onPlayerQuit}) and on {@link #shutdown()}.
 * Pending rejoins hold UUIDs, never {@link Player} references.
 */
public final class VoiceSessionService implements RejoinVoicechatPlugin.ConnectionListener {

    private static final long RETRY_DELAY_TICKS = 20L;
    /**
     * A normal handshake logs "validated" about two seconds after "authenticated". If it is still
     * half-open after this, the ack is being sent to the first UDP address and the client (which
     * retries every second) has not seen it.
     */
    private static final long AUTH_REFRESH_MILLIS = 2500L;
    /** Plugin channel the SVC client mod registers to receive its connection secret. */
    private static final String SECRET_CHANNEL = "voicechat:secret";

    private final Plugin plugin;
    private final VoicechatBridge bridge;
    /** Swapped atomically by {@link #reload}; readers take one snapshot per operation where it matters. */
    private volatile PluginSettings settings;
    private volatile Messages messages;
    private final Logger logger;
    private final AsyncScheduler async;

    private final Map<UUID, PendingRejoin> pendingRejoins = new ConcurrentHashMap<>();
    /** Target UUID -> {@link System#nanoTime()} at which the rejoin cooldown ends. */
    private final Map<UUID, Long> cooldownExpiry = new ConcurrentHashMap<>();
    /** Players that ran /voicechat disconnect: the watchdog must leave them alone. */
    private final Set<UUID> manuallyDisconnected = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> autoAttempts = new ConcurrentHashMap<>();
    /** Join-hint checks already scheduled. Separate from lost checks so one does not block the other. */
    private final Set<UUID> queuedJoinChecks = ConcurrentHashMap.newKeySet();
    /** Connection-lost checks already scheduled. */
    private final Set<UUID> queuedLostChecks = ConcurrentHashMap.newKeySet();

    private final Object queueLock = new Object();
    /** Automatic rejoins waiting for a free slot. Manual commands never enter this queue. */
    private final ArrayDeque<Waiting> waitQueue = new ArrayDeque<>();
    /** Disconnect order for the current incident. The ticket is the player's place in that incident. */
    private final Map<UUID, Integer> waveTickets = new ConcurrentHashMap<>();
    /** How many players have dropped in the current incident. Stays put until the incident goes idle. */
    private int waveTotal;
    /** Automatic rejoins currently running (not waiting). */
    private int automaticInFlight;

    private volatile boolean active = true;

    public VoiceSessionService(Plugin plugin, VoicechatBridge bridge, PluginSettings settings, Messages messages) {
        this.plugin = plugin;
        this.bridge = bridge;
        this.settings = settings;
        this.messages = messages;
        this.logger = plugin.getLogger();
        this.async = plugin.getServer().getAsyncScheduler();
    }

    public Messages messages() {
        return messages;
    }

    /** Applies a reloaded config/messages pair. In-flight rejoins keep the timings they started with. */
    public void reload(PluginSettings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
        if (!settings.rejoinQueueEnabled()) {
            launchWaiting();
        }
    }

    /**
     * @return whether the player's client runs a compatible Simple Voice Chat mod: it must listen on
     * SVC's secret channel <em>and</em> SVC must have accepted its version handshake. Players
     * without the mod fail both checks and are never targeted by auto-rejoin or hints.
     */
    private boolean hasVoicechatMod(Player player) {
        return player.getListeningPluginChannels().contains(SECRET_CHANNEL) && bridge.isCompatible(player.getUniqueId());
    }

    // ------------------------------------------------------------------------------------------
    // /voicechat rejoin
    // ------------------------------------------------------------------------------------------

    /** Handles a manual {@code /voicechat rejoin}. Must be called from the command (main) thread. */
    public void requestRejoin(CommandSender requester, Player target) {
        UUID targetId = target.getUniqueId();
        boolean self = isSelf(requester, targetId);

        try {
            if (!bridge.isVoiceServerRunning()) {
                messages.send(requester, "voicechat-unavailable");
                return;
            }
            if (settings.requireCompatibleClient() && !hasVoicechatMod(target)) {
                messages.send(requester, "not-compatible", Messages.player(target.getName()));
                return;
            }
        } catch (BridgeException e) {
            logger.log(Level.SEVERE, "Failed to query Simple Voice Chat state", e);
            messages.send(requester, "rejoin-error");
            return;
        }

        long remainingSeconds = remainingCooldownSeconds(targetId);
        if (remainingSeconds > 0 && !requester.hasPermission(Permissions.BYPASS_COOLDOWN)) {
            messages.send(requester, "cooldown", Messages.number("seconds", remainingSeconds));
            return;
        }

        if (!startRejoin(requester, target, false)) {
            messages.send(requester, "rejoin-already-pending", Messages.player(target.getName()));
            return;
        }

        if (settings.rejoinCooldownMillis() > 0) {
            cooldownExpiry.put(targetId, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.rejoinCooldownMillis()));
        }

        if (self) {
            messages.send(requester, "rejoin-started");
        } else {
            messages.send(requester, "rejoin-started-other", Messages.player(target.getName()));
            if (settings.notifyRejoinTarget()) {
                messages.send(target, "rejoin-started-by-admin");
            }
        }
    }

    /**
     * Registers the pending rejoin and kicks off the async reset.
     *
     * @return {@code false} if a rejoin is already in progress for this player
     */
    private boolean startRejoin(@Nullable CommandSender requester, Player target, boolean automatic) {
        UUID targetId = target.getUniqueId();
        synchronized (queueLock) {
            waitQueue.removeIf(waiting -> waiting.playerId.equals(targetId));
        }
        PendingRejoin pending = PendingRejoin.of(requester, targetId, automatic);
        if (pendingRejoins.putIfAbsent(targetId, pending) != null) {
            return false;
        }
        manuallyDisconnected.remove(targetId);
        pending.timeoutTask = async.runDelayed(plugin, task -> onRejoinTimeout(targetId, pending),
                settings.rejoinTimeoutSeconds(), TimeUnit.SECONDS);

        async.runNow(plugin, task -> resetSession(targetId, pending));
        return true;
    }

    /**
     * Step 1 (async): clear the server session, then send a real secret. The client closes its old
     * socket itself when that secret arrives, which is the same path SVC uses after a timeout.
     */
    private void resetSession(UUID targetId, PendingRejoin pending) {
        if (!isStillPending(targetId, pending)) {
            return;
        }
        try {
            if (!bridge.disconnect(targetId)) {
                failRejoin(targetId, pending, "voicechat-unavailable", null);
                return;
            }
        } catch (BridgeException e) {
            failRejoin(targetId, pending, "rejoin-error", e);
            return;
        }

        long delay = settings.reconnectDelayMillis();
        debug("Voice session of %s reset, sending new secret in %d ms", targetId, delay);
        if (delay == 0) {
            sendNewSecret(targetId, pending);
        } else {
            async.runDelayed(plugin, task -> sendNewSecret(targetId, pending), delay, TimeUnit.MILLISECONDS);
        }
    }

    /** Step 2 (async): send a fresh secret; the client reconnects and {@link #onVoiceConnected} fires. */
    private void sendNewSecret(UUID targetId, PendingRejoin pending) {
        if (!isStillPending(targetId, pending)) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            discardPending(targetId, pending);
            return;
        }
        try {
            if (!bridge.initializeConnection(target)) {
                failRejoin(targetId, pending, "voicechat-unavailable", null);
                return;
            }
        } catch (BridgeException e) {
            failRejoin(targetId, pending, "rejoin-error", e);
            return;
        }
        pending.secretSent = true;
        pending.secretSentAt = System.nanoTime();
        debug("New voice secret sent to %s", target.getName());

        // Success is SVC's own PlayerConnectedEvent. One refresh at 2.5s retargets a stuck ack,
        // and the timeout does the single backup check in case that event was missed.
        pending.authRefreshTask = async.runDelayed(plugin, task -> refreshAuthentication(targetId, pending),
                AUTH_REFRESH_MILLIS, TimeUnit.MILLISECONDS);
        if (!isStillPending(targetId, pending)) {
            pending.cancelTasks();
        }
    }

    /** One shot, 2.5s after the secret: if the ack is stuck on the first UDP address, forget it. */
    private void refreshAuthentication(UUID targetId, PendingRejoin pending) {
        if (!isStillPending(targetId, pending)) {
            return;
        }
        try {
            if (bridge.hasActiveConnection(targetId)) {
                debug("Connection of %s detected at the authentication refresh", targetId);
                onVoiceConnected(targetId);
                return;
            }
            String stuckAt = bridge.forgetPendingAuthentication(targetId);
            if (stuckAt != null) {
                Player player = Bukkit.getPlayer(targetId);
                logger.info("Voice handshake of " + (player != null ? player.getName() : targetId)
                        + " is stuck after authentication from " + stuckAt
                        + "; the next authenticate packet will be answered at its own address");
            }
        } catch (BridgeException e) {
            logger.log(Level.WARNING, "Could not refresh the voice handshake of " + targetId, e);
        }
    }

    private void onRejoinTimeout(UUID targetId, PendingRejoin pending) {
        if (pending.secretSent() && isStillPending(targetId, pending) && connectedQuietly(targetId)) {
            onVoiceConnected(targetId);
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        // Schedule the retry first so this player still counts as busy and the incident keeps its tickets.
        boolean retry = pending.automatic && target != null
                && autoAttempts.getOrDefault(targetId, 0) < settings.autoRejoinMaxAttempts();
        if (retry) {
            scheduleWatchdog(target, RETRY_DELAY_TICKS, Trigger.CONNECTION_LOST);
        }
        if (!discardPending(targetId, pending)) {
            return;
        }
        if (target == null) {
            return;
        }
        debug("Rejoin of %s timed out", target.getName());
        if (retry) {
            return;
        }

        long seconds = settings.rejoinTimeoutSeconds();
        messages.send(target, "rejoin-timeout", Messages.number("seconds", seconds));
        messages.sendTroubleshooting(target);

        if (pending.automatic) {
            if (settings.hintOnConnectionLost() && target.hasPermission(Permissions.USE)) {
                messages.send(target, Trigger.CONNECTION_LOST.hintKey);
            }
            return;
        }
        Audience requester = pending.requesterAudience(targetId);
        if (requester != null) {
            messages.send(requester, "rejoin-timeout-other",
                    Messages.player(target.getName()), Messages.number("seconds", seconds));
        }
    }

    private void failRejoin(UUID targetId, PendingRejoin pending, String messageKey, @Nullable Throwable error) {
        if (error != null) {
            logger.log(Level.SEVERE, "Failed to rejoin voice chat for " + targetId, error);
        }
        if (!discardPending(targetId, pending)) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        Audience requester = pending.requesterAudience(targetId);
        Audience audience = requester != null ? requester : target;
        if (audience != null) {
            messages.send(audience, messageKey);
        }
    }

    // ------------------------------------------------------------------------------------------
    // /voicechat disconnect
    // ------------------------------------------------------------------------------------------

    /** Handles {@code /voicechat disconnect}. Must be called from the command (main) thread. */
    public void requestDisconnect(CommandSender requester, Player target) {
        UUID targetId = target.getUniqueId();
        String targetName = target.getName();
        boolean self = isSelf(requester, targetId);

        try {
            if (!bridge.isVoiceServerRunning()) {
                messages.send(requester, "voicechat-unavailable");
                return;
            }
            if (pendingRejoins.containsKey(targetId)) {
                messages.send(requester, "rejoin-already-pending", Messages.player(targetName));
                return;
            }
            if (!bridge.hasActiveConnection(targetId)) {
                messages.send(requester, "not-connected", Messages.player(targetName));
                return;
            }
        } catch (BridgeException e) {
            logger.log(Level.SEVERE, "Failed to query Simple Voice Chat state", e);
            messages.send(requester, "disconnect-error");
            return;
        }

        manuallyDisconnected.add(targetId);
        async.runNow(plugin, task -> {
            try {
                if (!bridge.disconnect(targetId)) {
                    manuallyDisconnected.remove(targetId);
                    messages.send(requester, "voicechat-unavailable");
                    return;
                }
            } catch (BridgeException e) {
                manuallyDisconnected.remove(targetId);
                logger.log(Level.SEVERE, "Failed to disconnect " + targetName + " from voice chat", e);
                messages.send(requester, "disconnect-error");
                return;
            }
            debug("%s disconnected from voice chat by %s", targetName, requester.getName());

            // The HUD icon only flips when the client itself closes its socket. An idle secret does
            // that within one RTT and leaves no socket behind, so the player stays disconnected.
            Player online = Bukkit.getPlayer(targetId);
            if (online != null) {
                try {
                    if (!bridge.releaseClient(online)) {
                        if (bridge.initializeConnection(online)) {
                            bridge.disconnect(targetId);
                        }
                    }
                } catch (BridgeException e) {
                    logger.log(Level.WARNING, "Could not make " + targetName + " drop the voice connection immediately", e);
                }
            }

            if (self) {
                messages.send(requester, "disconnect-success");
                return;
            }
            messages.send(requester, "disconnect-success-other", Messages.player(targetName));
            if (online != null && settings.notifyDisconnectTarget()) {
                messages.send(online, "disconnect-by-admin");
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // Voice chat events (SVC packet thread)
    // ------------------------------------------------------------------------------------------

    @Override
    public void onVoiceConnected(UUID playerId) {
        if (!active) {
            return;
        }
        manuallyDisconnected.remove(playerId);
        autoAttempts.remove(playerId);

        PendingRejoin pending = pendingRejoins.remove(playerId);
        if (pending == null) {
            return;
        }
        pending.cancelTasks();
        if (pending.automatic) {
            releaseAutomaticSlot();
        }

        Player target = Bukkit.getPlayer(playerId);
        if (target == null) {
            return;
        }
        debug("%s reconnected to voice chat", target.getName());
        messages.send(target, "rejoin-success");

        Audience requester = pending.requesterAudience(playerId);
        if (requester != null) {
            messages.send(requester, "rejoin-success-other", Messages.player(target.getName()));
        }
    }

    @Override
    public void onVoiceDisconnected(UUID playerId) {
        if (!active || !(settings.autoRejoinEnabled() || settings.hintOnConnectionLost())) {
            return;
        }
        // Disconnects we caused ourselves are expected and must not trigger the watchdog.
        if (pendingRejoins.containsKey(playerId) || manuallyDisconnected.contains(playerId)) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        synchronized (queueLock) {
            assignTicket(playerId);
        }
        scheduleWatchdog(player, settings.lostCheckDelayTicks(), Trigger.CONNECTION_LOST);
    }

    // ------------------------------------------------------------------------------------------
    // Watchdog: auto-rejoin and clickable hints
    // ------------------------------------------------------------------------------------------

    private enum Trigger {
        JOIN("hint-join"),
        CONNECTION_LOST("hint-connection-lost");

        final String hintKey;

        Trigger(String hintKey) {
            this.hintKey = hintKey;
        }
    }

    /**
     * Join hint only. A normal login already connects voice, so this never starts an automatic rejoin.
     * Players who never register the voice channel are not checked at all.
     */
    public void onPlayerJoin(Player player) {
        if (player.getListeningPluginChannels().contains(SECRET_CHANNEL)) {
            onPluginChannel(player, SECRET_CHANNEL);
        }
    }

    /** Called when the client registers a plugin channel. Only {@code voicechat:secret} matters. */
    public void onPluginChannel(Player player, String channel) {
        if (!SECRET_CHANNEL.equals(channel) || !player.isOnline() || !settings.hintOnJoin()) {
            return;
        }
        scheduleWatchdog(player, settings.joinCheckDelayTicks(), Trigger.JOIN);
    }

    /**
     * Queues one delayed connection check on the player's own scheduler. Paper retires the task
     * automatically if the player leaves, so nothing outlives the player's session.
     */
    private void scheduleWatchdog(Player player, long delayTicks, Trigger trigger) {
        UUID playerId = player.getUniqueId();
        Set<UUID> queued = trigger == Trigger.JOIN ? queuedJoinChecks : queuedLostChecks;
        if (!active || !queued.add(playerId)) {
            return;
        }
        ScheduledTask task = player.getScheduler().runDelayed(plugin,
                t -> runWatchdog(player, trigger),
                () -> queued.remove(playerId),
                delayTicks);
        if (task == null) {
            queued.remove(playerId);
        }
    }

    private void runWatchdog(Player player, Trigger trigger) {
        UUID playerId = player.getUniqueId();
        Set<UUID> queued = trigger == Trigger.JOIN ? queuedJoinChecks : queuedLostChecks;
        try {
            if (!active || !player.isOnline()
                    || pendingRejoins.containsKey(playerId) || manuallyDisconnected.contains(playerId)) {
                return;
            }
            try {
                if (!bridge.isVoiceServerRunning() || bridge.hasActiveConnection(playerId)) {
                    return;
                }
                if (!hasVoicechatMod(player)) {
                    debug("Watchdog skipped %s: Simple Voice Chat mod not detected on the client", player.getName());
                    return;
                }
            } catch (BridgeException e) {
                logger.log(Level.WARNING, "Voice chat watchdog could not query Simple Voice Chat", e);
                return;
            }

            if (trigger == Trigger.CONNECTION_LOST && settings.autoRejoinEnabled()) {
                int nextAttempt = autoAttempts.getOrDefault(playerId, 0) + 1;
                if (nextAttempt <= settings.autoRejoinMaxAttempts()) {
                    offerAutomatic(player);
                    return;
                }
            }

            boolean hintEnabled = trigger == Trigger.JOIN ? settings.hintOnJoin() : settings.hintOnConnectionLost();
            if (hintEnabled && player.hasPermission(Permissions.USE)) {
                messages.send(player, trigger.hintKey);
            }
        } finally {
            queued.remove(playerId);
            if (trigger == Trigger.CONNECTION_LOST) {
                clearWaveIfIdle();
            }
        }
    }

    /**
     * Starts an automatic rejoin now, or parks the player when the concurrent cap is enabled and full.
     * The attempt counter moves only when the rejoin actually starts.
     */
    private void offerAutomatic(Player player) {
        UUID playerId = player.getUniqueId();
        boolean launchNow;
        int position;
        int total;
        synchronized (queueLock) {
            if (pendingRejoins.containsKey(playerId) || containsWaiting(playerId)) {
                return;
            }
            position = assignTicket(playerId);
            total = waveTotal;
            if (!settings.rejoinQueueEnabled() || automaticInFlight < settings.maxConcurrentRejoins()) {
                automaticInFlight++;
                launchNow = true;
            } else {
                waitQueue.addLast(new Waiting(playerId, position));
                launchNow = false;
            }
        }
        if (launchNow) {
            if (!beginAutoRejoin(player, false, position, total)) {
                releaseAutomaticSlot();
            }
            return;
        }
        debug("Queued automatic rejoin of %s at %d/%d", player.getName(), position, total);
        messages.send(player, "rejoin-queued",
                Messages.number("position", position), Messages.number("total", total));
    }

    /** @return {@code false} when the rejoin did not start and the reserved slot must be released. */
    private boolean beginAutoRejoin(Player player, boolean fromQueue, int position, int total) {
        UUID playerId = player.getUniqueId();
        int attempt = autoAttempts.merge(playerId, 1, Integer::sum);
        if (attempt > settings.autoRejoinMaxAttempts()) {
            return false;
        }
        debug("Auto-rejoin %d/%d for %s", attempt, settings.autoRejoinMaxAttempts(), player.getName());
        if (fromQueue) {
            messages.send(player, "rejoin-queue-turn",
                    Messages.number("position", position), Messages.number("total", total),
                    Messages.number("attempt", attempt), Messages.number("max", settings.autoRejoinMaxAttempts()));
        } else {
            messages.send(player, "auto-rejoin",
                    Messages.number("attempt", attempt), Messages.number("max", settings.autoRejoinMaxAttempts()));
        }
        return startRejoin(null, player, true);
    }

    /** Drops one running automatic slot and starts whoever is waiting, up to the current cap. */
    private void releaseAutomaticSlot() {
        synchronized (queueLock) {
            automaticInFlight = Math.max(0, automaticInFlight - 1);
        }
        launchWaiting();
    }

    private void launchWaiting() {
        while (true) {
            List<Waiting> starting = new ArrayList<>();
            int total;
            synchronized (queueLock) {
                int limit = settings.rejoinQueueEnabled() ? settings.maxConcurrentRejoins() : Integer.MAX_VALUE;
                while (automaticInFlight < limit && !waitQueue.isEmpty()) {
                    Waiting waiting = waitQueue.pollFirst();
                    Player player = Bukkit.getPlayer(waiting.playerId);
                    if (player == null || !player.isOnline() || manuallyDisconnected.contains(waiting.playerId)) {
                        continue;
                    }
                    automaticInFlight++;
                    starting.add(waiting);
                }
                total = waveTotal;
                clearWaveIfIdleLocked();
            }
            boolean slotFreed = false;
            for (Waiting waiting : starting) {
                Player player = Bukkit.getPlayer(waiting.playerId);
                if (player != null && beginAutoRejoin(player, true, waiting.position, total)) {
                    continue;
                }
                synchronized (queueLock) {
                    automaticInFlight = Math.max(0, automaticInFlight - 1);
                }
                slotFreed = true;
            }
            if (!slotFreed) {
                return;
            }
        }
    }

    private boolean containsWaiting(UUID playerId) {
        for (Waiting waiting : waitQueue) {
            if (waiting.playerId.equals(playerId)) {
                return true;
            }
        }
        return false;
    }

    /** Caller holds {@link #queueLock}. */
    private int assignTicket(UUID playerId) {
        Integer existing = waveTickets.get(playerId);
        if (existing != null) {
            return existing;
        }
        int ticket = ++waveTotal;
        waveTickets.put(playerId, ticket);
        return ticket;
    }

    private void clearWaveIfIdle() {
        synchronized (queueLock) {
            clearWaveIfIdleLocked();
        }
    }

    /** Caller holds {@link #queueLock}. */
    private void clearWaveIfIdleLocked() {
        if (automaticInFlight == 0 && waitQueue.isEmpty() && queuedLostChecks.isEmpty()) {
            waveTickets.clear();
            waveTotal = 0;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle / cleanup
    // ------------------------------------------------------------------------------------------

    public void onPlayerQuit(UUID playerId) {
        PendingRejoin pending = pendingRejoins.remove(playerId);
        boolean automatic = pending != null && pending.automatic;
        if (pending != null) {
            pending.cancelTasks();
        }
        synchronized (queueLock) {
            waitQueue.removeIf(waiting -> waiting.playerId.equals(playerId));
            waveTickets.remove(playerId);
        }
        queuedJoinChecks.remove(playerId);
        queuedLostChecks.remove(playerId);
        if (automatic) {
            releaseAutomaticSlot();
        } else {
            clearWaveIfIdle();
        }
        cooldownExpiry.remove(playerId);
        manuallyDisconnected.remove(playerId);
        autoAttempts.remove(playerId);
    }

    public void shutdown() {
        active = false;
        synchronized (queueLock) {
            waitQueue.clear();
            waveTickets.clear();
            waveTotal = 0;
            automaticInFlight = 0;
        }
        pendingRejoins.values().forEach(PendingRejoin::cancelTasks);
        pendingRejoins.clear();
        cooldownExpiry.clear();
        manuallyDisconnected.clear();
        autoAttempts.clear();
        queuedJoinChecks.clear();
        queuedLostChecks.clear();
        async.cancelTasks(plugin);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private long remainingCooldownSeconds(UUID targetId) {
        Long expiry = cooldownExpiry.get(targetId);
        if (expiry == null) {
            return 0L;
        }
        long remainingNanos = expiry - System.nanoTime();
        if (remainingNanos <= 0) {
            cooldownExpiry.remove(targetId, expiry);
            return 0L;
        }
        return (remainingNanos + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
    }

    private boolean connectedQuietly(UUID targetId) {
        try {
            return bridge.hasActiveConnection(targetId);
        } catch (BridgeException e) {
            return false;
        }
    }

    private boolean isStillPending(UUID targetId, PendingRejoin pending) {
        return active && pendingRejoins.get(targetId) == pending;
    }

    private boolean discardPending(UUID targetId, PendingRejoin pending) {
        if (!pendingRejoins.remove(targetId, pending)) {
            return false;
        }
        pending.cancelTasks();
        if (pending.automatic) {
            releaseAutomaticSlot();
        }
        return true;
    }

    private static boolean isSelf(CommandSender sender, UUID targetId) {
        return sender instanceof Player player && player.getUniqueId().equals(targetId);
    }

    private void debug(String format, Object... args) {
        if (settings.debug()) {
            logger.info("[debug] " + String.format(format, args));
        }
    }

    /**
     * An in-flight rejoin. Identity matters: async steps compare against the map entry so a stale
     * step (after timeout, quit or a newer request) never acts on someone else's attempt.
     */
    private static final class PendingRejoin {
        /** Player who issued the command, or {@code null} for console/automatic/self. */
        private final @Nullable UUID requesterId;
        private final boolean consoleRequester;
        private final boolean automatic;
        private volatile boolean secretSent;
        private volatile long secretSentAt;
        private volatile @Nullable ScheduledTask timeoutTask;
        private volatile @Nullable ScheduledTask authRefreshTask;

        private PendingRejoin(@Nullable UUID requesterId, boolean consoleRequester, boolean automatic) {
            this.requesterId = requesterId;
            this.consoleRequester = consoleRequester;
            this.automatic = automatic;
        }

        static PendingRejoin of(@Nullable CommandSender requester, UUID targetId, boolean automatic) {
            UUID requesterId = requester instanceof Player player && !player.getUniqueId().equals(targetId)
                    ? player.getUniqueId()
                    : null;
            return new PendingRejoin(requesterId, requester instanceof ConsoleCommandSender, automatic);
        }

        /** @return who should get "-other" feedback, or {@code null} when the target ran it themselves. */
        @Nullable Audience requesterAudience(UUID targetId) {
            if (requesterId != null && !requesterId.equals(targetId)) {
                return Bukkit.getPlayer(requesterId);
            }
            return consoleRequester ? Bukkit.getConsoleSender() : null;
        }

        boolean secretSent() {
            return secretSent;
        }

        void cancelTasks() {
            ScheduledTask timeout = timeoutTask;
            if (timeout != null) {
                timeout.cancel();
            }
            ScheduledTask refresh = authRefreshTask;
            if (refresh != null) {
                refresh.cancel();
            }
        }
    }

    /** One automatic rejoin waiting for a free slot, with the place it had when it joined the line. */
    private record Waiting(UUID playerId, int position) {
    }
}
