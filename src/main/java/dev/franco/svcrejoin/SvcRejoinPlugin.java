package dev.franco.svcrejoin;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import dev.franco.svcrejoin.command.AdminCommand;
import dev.franco.svcrejoin.command.VoicechatSubcommands;
import dev.franco.svcrejoin.config.Messages;
import dev.franco.svcrejoin.config.PluginSettings;
import dev.franco.svcrejoin.listener.PlayerSessionListener;
import dev.franco.svcrejoin.service.VoiceSessionService;
import dev.franco.svcrejoin.voice.RejoinVoicechatPlugin;
import dev.franco.svcrejoin.voice.VoicechatBridge;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * SVCRejoin: adds {@code /voicechat rejoin} and {@code /voicechat disconnect} to Simple Voice Chat.
 *
 * <p>Wiring order matters: the SVC API plugin must be registered during {@code onEnable}, because SVC
 * collects addon registrations on {@code ServerLoadEvent}, after every plugin has been enabled.</p>
 */
public final class SvcRejoinPlugin extends JavaPlugin {

    private VoiceSessionService service;
    private RejoinVoicechatPlugin voicechatPlugin;
    private VoicechatSubcommands commands;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginSettings settings = PluginSettings.load(getConfig());
        Messages messages = Messages.load(this, settings);

        Plugin voicechat = getServer().getPluginManager().getPlugin("voicechat");
        BukkitVoicechatService voicechatService = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (voicechat == null || voicechatService == null) {
            getLogger().severe("Simple Voice Chat is not loaded; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        VoicechatBridge bridge;
        try {
            bridge = VoicechatBridge.create(voicechat);
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Unsupported Simple Voice Chat version "
                    + voicechat.getPluginMeta().getVersion() + " (internal layout changed); disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        service = new VoiceSessionService(this, bridge, settings, messages);
        voicechatPlugin = new RejoinVoicechatPlugin(service);
        voicechatService.registerPlugin(voicechatPlugin);

        getServer().getPluginManager().registerEvents(new PlayerSessionListener(service), this);
        for (Player online : Bukkit.getOnlinePlayers()) {
            service.onPlayerJoin(online);
        }
        commands = VoicechatSubcommands.register(this, voicechat, service);
        AdminCommand.register(this);

        getLogger().info("Hooked into Simple Voice Chat " + voicechat.getPluginMeta().getVersion()
                + " (" + bridge.layout() + " build, commands via " + commands.strategy() + ")");
        if (!bridge.canReleaseClient()) {
            getLogger().warning("This Simple Voice Chat build cannot drop the client socket before a rejoin.");
        }
    }

    public Messages messages() {
        return service.messages();
    }

    /**
     * Re-reads {@code config.yml} and {@code messages.yml}. Both files are parsed first: on a YAML
     * error nothing is applied, so a typo never leaves the plugin half-configured.
     */
    public void reloadSettings(CommandSender sender) {
        for (String fileName : new String[]{"config.yml", Messages.FILE_NAME}) {
            File file = new File(getDataFolder(), fileName);
            if (!file.exists()) {
                continue;
            }
            try {
                new YamlConfiguration().load(file);
            } catch (IOException | InvalidConfigurationException e) {
                getLogger().log(Level.WARNING, "Reload aborted, invalid " + fileName, e);
                service.messages().send(sender, "reload-error", Placeholder.unparsed("file", fileName));
                return;
            }
        }

        saveDefaultConfig();
        reloadConfig();
        PluginSettings settings = PluginSettings.load(getConfig());
        Messages messages = Messages.load(this, settings);
        service.reload(settings, messages);
        messages.send(sender, "reload-success");
    }

    @Override
    public void onDisable() {
        if (commands != null) {
            commands.unregister();
            commands = null;
        }
        // SVC cannot unregister API plugins: detaching turns our registered listeners into no-ops
        // and lets this plugin instance be garbage-collected.
        if (voicechatPlugin != null) {
            voicechatPlugin.detach();
            voicechatPlugin = null;
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }
}
