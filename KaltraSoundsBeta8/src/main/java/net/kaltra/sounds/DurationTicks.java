package net.kaltra.sounds;

import java.util.Locale;

final class DurationTicks {
    private DurationTicks() {}

    static long parsePositive(String raw) {
        if (raw == null) throw new IllegalArgumentException("Replay period is required.");
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException("Replay period is required.");

        long multiplier = 1L;
        if (value.endsWith("ticks")) {
            value = value.substring(0, value.length() - 5).trim();
        } else if (value.endsWith("tick")) {
            value = value.substring(0, value.length() - 4).trim();
        } else if (value.endsWith("t")) {
            value = value.substring(0, value.length() - 1).trim();
        } else if (value.endsWith("seconds")) {
            multiplier = 20L;
            value = value.substring(0, value.length() - 7).trim();
        } else if (value.endsWith("second")) {
            multiplier = 20L;
            value = value.substring(0, value.length() - 6).trim();
        } else if (value.endsWith("s")) {
            multiplier = 20L;
            value = value.substring(0, value.length() - 1).trim();
        } else if (value.endsWith("minutes")) {
            multiplier = 1_200L;
            value = value.substring(0, value.length() - 7).trim();
        } else if (value.endsWith("minute")) {
            multiplier = 1_200L;
            value = value.substring(0, value.length() - 6).trim();
        } else if (value.endsWith("m")) {
            multiplier = 1_200L;
            value = value.substring(0, value.length() - 1).trim();
        }

        try {
            long amount = Long.parseLong(value);
            if (amount <= 0L) throw invalid();
            return Math.multiplyExact(amount, multiplier);
        } catch (NumberFormatException | ArithmeticException ignored) {
            throw invalid();
        }
    }

    static String describe(long ticks) {
        if (ticks % 1_200L == 0L) return (ticks / 1_200L) + "m (" + ticks + " ticks)";
        if (ticks % 20L == 0L) return (ticks / 20L) + "s (" + ticks + " ticks)";
        return ticks + " ticks";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Replay period must be a positive duration such as 1200t, 60s, or 3m.");
    }
}
