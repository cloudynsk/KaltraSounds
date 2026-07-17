package net.kaltra.sounds;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

record SoundSpec(
        String key,
        float volume,
        float pitch,
        long delay,
        double radius,
        String category,
        String triggerPermission,
        String listenPermission,
        boolean ignoresDisabled
) {
    SoundSpec scaledVolume(float multiplier) {
        float safe = Float.isFinite(multiplier) ? Math.max(0.0f, multiplier) : 1.0f;
        return new SoundSpec(key, volume * safe, pitch, delay, radius, category, triggerPermission, listenPermission, ignoresDisabled);
    }

    static List<SoundSpec> fromRule(ConfigurationSection rule) {
        if (rule == null || !rule.getBoolean("Enabled", false)) return Collections.emptyList();
        ConfigurationSection sounds = rule.getConfigurationSection("Sounds");
        if (sounds == null) return Collections.emptyList();
        List<SoundSpec> result = new ArrayList<>();
        for (String id : sounds.getKeys(false)) {
            ConfigurationSection sec = sounds.getConfigurationSection(id);
            if (sec == null) continue;
            String key = sec.getString("Sound", "").trim();
            if (key.isEmpty()) continue;
            ConfigurationSection options = sec.getConfigurationSection("Options");
            double radius = options == null ? 0.0 : options.getDouble("Radius", 0.0);
            String trigger = options == null ? "" : options.getString("Permission Required", "");
            String listen = options == null ? "" : options.getString("Permission To Listen", "");
            boolean ignores = options != null && options.getBoolean("Ignores Disabled", false);
            float volume = finiteFloat(sec.getDouble("Volume", 1.0), 1.0f, true);
            float pitch = finiteFloat(sec.getDouble("Pitch", 1.0), 1.0f, false);
            radius = finiteRadius(radius);
            result.add(new SoundSpec(
                    key,
                    volume,
                    pitch,
                    Math.max(0L, sec.getLong("Delay", 0L)),
                    radius,
                    sec.getString("Category", "MASTER"),
                    trigger == null ? "" : trigger,
                    listen == null ? "" : listen,
                    ignores
            ));
        }
        return result;
    }

    private static float finiteFloat(double value, float fallback, boolean allowZero) {
        if (!Double.isFinite(value)) return fallback;
        if (allowZero ? value < 0.0 : value <= 0.0) return fallback;
        return (float) Math.min(value, Float.MAX_VALUE);
    }

    private static double finiteRadius(double value) {
        if (!Double.isFinite(value)) return 0.0;
        if (value == -1.0 || value == -2.0 || value >= 0.0) return value;
        return 0.0;
    }
}
