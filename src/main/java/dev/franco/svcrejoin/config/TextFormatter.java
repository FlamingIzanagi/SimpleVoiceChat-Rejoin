package dev.franco.svcrejoin.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts legacy Minecraft formatting into MiniMessage tags so a single string may freely mix:
 * <ul>
 *     <li>{@code &a}, {@code &l}, {@code &r}... (and the {@code §} variant)</li>
 *     <li>{@code &#RRGGBB} and the Bukkit form {@code &x&R&R&G&G&B&B}</li>
 *     <li>native MiniMessage ({@code <green>}, {@code <#55ffff>}, {@code <gradient>}, {@code <click>}...)</li>
 * </ul>
 * Conversion happens once when messages are loaded, never per send.
 *
 * <p>Unlike legacy chat, a MiniMessage color does not clear bold/italic; use {@code &r} for that.</p>
 */
final class TextFormatter {

    private static final Pattern BUKKIT_HEX = Pattern.compile(
            "[&§]x[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])");
    private static final Pattern AMPERSAND_HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_CODE = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

    private TextFormatter() {
    }

    static String toMiniMessage(String input) {
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) {
            return input;
        }
        String result = BUKKIT_HEX.matcher(input).replaceAll("<#$1$2$3$4$5$6>");
        result = AMPERSAND_HEX.matcher(result).replaceAll("<#$1>");

        Matcher matcher = LEGACY_CODE.matcher(result);
        StringBuilder builder = new StringBuilder(result.length() + 16);
        while (matcher.find()) {
            matcher.appendReplacement(builder, Matcher.quoteReplacement(tagFor(Character.toLowerCase(matcher.group(1).charAt(0)))));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static String tagFor(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            default -> "<reset>";
        };
    }
}
