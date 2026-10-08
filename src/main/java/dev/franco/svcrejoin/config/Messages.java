package dev.franco.svcrejoin.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Message catalogue backed by {@code messages.yml}. Every entry accepts {@code &} codes, hex colors
 * ({@code &#RRGGBB}, {@code <#RRGGBB>}) and MiniMessage; legacy codes are converted once at load time.
 * Adventure audiences are thread-safe on Paper, so these methods may be called from async tasks.
 */
public final class Messages {

    public static final String FILE_NAME = "messages.yml";
    private static final String TIPS_KEY = "troubleshooting-tips";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Map<String, String> templates;
    private final String prefix;
    private final String tipFormat;
    private final List<String> tips;

    private Messages(Map<String, String> templates, String prefix, String tipFormat, List<String> tips) {
        this.templates = templates;
        this.prefix = prefix;
        this.tipFormat = tipFormat;
        this.tips = tips;
    }

    public static Messages load(JavaPlugin plugin, PluginSettings settings) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        // Bundled defaults keep messages added in newer versions working with an older messages.yml.
        try (InputStream stream = plugin.getResource(FILE_NAME)) {
            if (stream != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled " + FILE_NAME, e);
        }

        Set<String> keys = new HashSet<>(yaml.getKeys(false));
        if (yaml.getDefaults() != null) {
            keys.addAll(yaml.getDefaults().getKeys(false));
        }
        keys.remove(TIPS_KEY);

        Map<String, String> templates = new HashMap<>();
        for (String key : keys) {
            String value = yaml.getString(key);
            if (value != null) {
                templates.put(key, TextFormatter.toMiniMessage(value));
            }
        }

        String prefix = templates.getOrDefault("prefix", "");
        String tipFormat = templates.getOrDefault("tip-format", "<gray><tip>");

        // Blank tips are dropped; a blank tip-format disables the tip lines entirely.
        List<String> tips = settings.troubleshootingEnabled() && !tipFormat.isBlank()
                ? yaml.getStringList(TIPS_KEY).stream().filter(tip -> !tip.isBlank()).map(TextFormatter::toMiniMessage).toList()
                : List.of();

        return new Messages(Map.copyOf(templates), prefix, tipFormat, tips);
    }

    /**
     * Sends a prefixed message. Blank or missing templates are silently skipped,
     * which lets admins disable any message by setting it to {@code ""}.
     */
    public void send(Audience audience, String key, TagResolver... placeholders) {
        String template = templates.get(key);
        if (template == null || template.isBlank()) {
            return;
        }
        audience.sendMessage(MINI_MESSAGE.deserialize(prefix + template, placeholders));
    }

    /** Like {@link #send} but without the prefix (help listings, multi-line output). */
    public void sendWithoutPrefix(Audience audience, String key, TagResolver... placeholders) {
        String template = templates.get(key);
        if (template == null || template.isBlank()) {
            return;
        }
        audience.sendMessage(MINI_MESSAGE.deserialize(template, placeholders));
    }

    /** @return the converted MiniMessage template, or {@code ""} if missing. */
    public String raw(String key) {
        return templates.getOrDefault(key, "");
    }

    public void sendTroubleshooting(Audience audience) {
        if (tips.isEmpty()) {
            return;
        }
        send(audience, "tips-header");
        for (String tip : tips) {
            audience.sendMessage(MINI_MESSAGE.deserialize(tipFormat, Placeholder.parsed("tip", tip)));
        }
    }

    public static TagResolver player(String name) {
        return Placeholder.unparsed("player", name);
    }

    public static TagResolver number(String key, long value) {
        return Placeholder.unparsed(key, Long.toString(value));
    }
}
