package net.kaltra.sounds;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.Map;

final class Text {
    private Text() {}

    static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    static String replace(String value, Map<String, String> values) {
        String out = value == null ? "" : value;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }

    static void send(CommandSender sender, String prefix, String message) {
        sender.sendMessage(color(prefix + message));
    }
}
