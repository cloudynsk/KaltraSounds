package net.kaltra.sounds;

/** Dependency-free replay-period parsing verification executed by Gradle's check task. */
public final class DurationTicksVerification {
    private DurationTicksVerification() {}

    public static void main(String[] args) {
        expect("1200", 1_200L);
        expect("1200t", 1_200L);
        expect("60s", 1_200L);
        expect("3m", 3_600L);
        expect(" 2 minutes ", 2_400L);
        reject("0");
        reject("-5s");
        reject("1.5m");
        reject("forever");
        reject("9223372036854775807m");
    }

    private static void expect(String input, long ticks) {
        long actual = DurationTicks.parsePositive(input);
        if (actual != ticks) throw new AssertionError(input + " parsed as " + actual + ", expected " + ticks);
    }

    private static void reject(String input) {
        try {
            DurationTicks.parsePositive(input);
            throw new AssertionError(input + " should have been rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
