package net.kaltra.sounds;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

record LoopSettings(
        long delay,
        long period,
        long periodRandomness,
        int maximumPlays,
        FadeIn fadeIn,
        StopOnExit stopOnExit
) {
    private static final int MAX_FADE_PLAYS = 1_000;

    static LoopSettings from(ConfigurationSection loop) {
        long delay = Math.max(0L, longValue(loop, "Delay", 0L));
        long period = Math.max(1L, longValue(loop, "Period", 100L));
        long randomness = Math.max(0L, longValue(loop, "Period Randomness", 0L));
        int maximumPlays = Math.max(0, intValue(loop, "Maximum Plays", 0));

        ConfigurationSection fadeInSection = section(loop, "Fade In");
        FadeIn fadeIn = new FadeIn(
                fadeInSection != null && booleanValue(fadeInSection, "Enabled", false),
                clampFadePlays(intValue(fadeInSection, "Plays", 3)),
                multiplier(doubleValue(fadeInSection, "Start Volume Multiplier", 0.2)),
                Curve.parse(stringValue(fadeInSection, "Curve", "SMOOTHSTEP"))
        );

        ConfigurationSection stopSection = section(loop, "Stop On Exit");
        ConfigurationSection fadeOutSection = section(stopSection, "Fade Out");
        FadeOut fadeOut = new FadeOut(
                fadeOutSection != null && booleanValue(fadeOutSection, "Enabled", false),
                clampFadePlays(intValue(fadeOutSection, "Plays", 3)),
                Math.max(1L, longValue(fadeOutSection, "Period", period)),
                multiplier(doubleValue(fadeOutSection, "End Volume Multiplier", 0.0)),
                Curve.parse(stringValue(fadeOutSection, "Curve", "SMOOTHSTEP"))
        );
        StopOnExit stopOnExit = new StopOnExit(
                stopSection != null && booleanValue(stopSection, "Enabled", false),
                Math.max(0L, longValue(stopSection, "Delay", 0L)),
                fadeOut
        );
        return new LoopSettings(delay, period, randomness, maximumPlays, fadeIn, stopOnExit);
    }

    long nextPeriod() {
        if (periodRandomness <= 0L) return period;
        long lower = periodRandomness >= period ? 1L : period - periodRandomness;
        long upper = period > Long.MAX_VALUE - periodRandomness ? Long.MAX_VALUE : period + periodRandomness;
        if (upper <= lower) return lower;
        if (upper == Long.MAX_VALUE) return ThreadLocalRandom.current().nextLong(lower, upper);
        return ThreadLocalRandom.current().nextLong(lower, upper + 1L);
    }

    float fadeInMultiplier(int playNumber) {
        if (!fadeIn.enabled() || fadeIn.plays() <= 1 || playNumber >= fadeIn.plays()) return 1.0f;
        double progress = Math.max(0.0, (playNumber - 1.0) / (fadeIn.plays() - 1.0));
        double curved = fadeIn.curve().apply(progress);
        return (float) (fadeIn.startMultiplier() + ((1.0 - fadeIn.startMultiplier()) * curved));
    }

    float fadeOutMultiplier(int playNumber) {
        FadeOut fadeOut = stopOnExit.fadeOut();
        if (!fadeOut.enabled() || fadeOut.plays() <= 0) return 0.0f;
        double progress = Math.min(1.0, Math.max(0.0, playNumber / (double) fadeOut.plays()));
        double curved = fadeOut.curve().apply(progress);
        return (float) (1.0 + ((fadeOut.endMultiplier() - 1.0) * curved));
    }

    private static int clampFadePlays(int value) {
        return Math.max(0, Math.min(MAX_FADE_PLAYS, value));
    }

    private static float multiplier(double value) {
        if (!Double.isFinite(value)) return 0.0f;
        return (float) Math.max(0.0, Math.min(1.0, value));
    }

    private static ConfigurationSection section(ConfigurationSection source, String name) {
        if (source == null) return null;
        for (String key : source.getKeys(false)) {
            if (key.equalsIgnoreCase(name)) return source.getConfigurationSection(key);
        }
        return null;
    }

    private static boolean booleanValue(ConfigurationSection source, String name, boolean fallback) {
        if (source == null) return fallback;
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getBoolean(key, fallback);
        return fallback;
    }

    private static int intValue(ConfigurationSection source, String name, int fallback) {
        if (source == null) return fallback;
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getInt(key, fallback);
        return fallback;
    }

    private static long longValue(ConfigurationSection source, String name, long fallback) {
        if (source == null) return fallback;
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getLong(key, fallback);
        return fallback;
    }

    private static double doubleValue(ConfigurationSection source, String name, double fallback) {
        if (source == null) return fallback;
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getDouble(key, fallback);
        return fallback;
    }

    private static String stringValue(ConfigurationSection source, String name, String fallback) {
        if (source == null) return fallback;
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getString(key, fallback);
        return fallback;
    }

    record FadeIn(boolean enabled, int plays, float startMultiplier, Curve curve) {}
    record StopOnExit(boolean enabled, long delay, FadeOut fadeOut) {}
    record FadeOut(boolean enabled, int plays, long period, float endMultiplier, Curve curve) {}

    enum Curve {
        LINEAR,
        SMOOTHSTEP,
        EXPONENTIAL;

        static Curve parse(String value) {
            if (value == null) return SMOOTHSTEP;
            try {
                return valueOf(value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return SMOOTHSTEP;
            }
        }

        double apply(double value) {
            double t = Math.max(0.0, Math.min(1.0, value));
            return switch (this) {
                case LINEAR -> t;
                case SMOOTHSTEP -> t * t * (3.0 - (2.0 * t));
                case EXPONENTIAL -> (Math.exp(4.0 * t) - 1.0) / (Math.exp(4.0) - 1.0);
            };
        }
    }
}
