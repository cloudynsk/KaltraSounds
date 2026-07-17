package net.kaltra.sounds;

import java.util.List;

/** Dependency-free verification executed by Gradle's check task. */
public final class SoundIconResolverVerification {
    private SoundIconResolverVerification() {}

    public static void main(String[] args) {
        expectFirst("ambient.nether_wastes.loop", "NETHERRACK");
        expectFirst("music.nether.basalt_deltas", "BASALT");
        expectContains("ambient.soul_sand_valley.loop", "SOUL_SAND");
        expectContains("ambient.crimson_forest.loop", "CRIMSON_NYLIUM");
        expectContains("ambient.warped_forest.loop", "WARPED_NYLIUM");
        expectContains("ambient.basalt_deltas.loop", "BASALT");
        expectContains("music.end", "END_STONE");
        expectContains("ambient.cave", "STONE");
        expectContains("block.sculk_sensor.clicking", "SCULK");
        expectNotContains("ambient.nether_wastes.loop", "ECHO_SHARD");
    }

    private static void expectFirst(String key, String... accepted) {
        List<String> candidates = SoundIconResolver.candidates(key);
        if (candidates.isEmpty()) throw new AssertionError(key + " returned no candidates");
        for (String value : accepted) if (candidates.getFirst().equals(value)) return;
        throw new AssertionError(key + " first icon was " + candidates.getFirst() + ", expected one of " + List.of(accepted));
    }

    private static void expectContains(String key, String value) {
        List<String> candidates = SoundIconResolver.candidates(key);
        if (!candidates.contains(value)) throw new AssertionError(key + " did not include " + value + ": " + candidates);
    }

    private static void expectNotContains(String key, String value) {
        List<String> candidates = SoundIconResolver.candidates(key);
        if (candidates.contains(value)) throw new AssertionError(key + " unexpectedly included " + value + ": " + candidates);
    }
}
