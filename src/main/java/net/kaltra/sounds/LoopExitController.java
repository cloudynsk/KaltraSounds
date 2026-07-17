package net.kaltra.sounds;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

final class LoopExitController {
    private LoopExitController() {}

    static List<BukkitTask> schedule(JavaPlugin plugin, SoundEngine engine, Player player,
                                     ConfigurationSection loop, List<String> sounds, Runnable cleanup) {
        if (loop == null || sounds.isEmpty()) return List.of();
        LoopSettings settings = LoopSettings.from(loop);
        LoopSettings.StopOnExit stop = settings.stopOnExit();
        if (!stop.enabled()) return List.of();

        LoopSettings.FadeOut fade = stop.fadeOut();
        if (!fade.enabled() || fade.plays() <= 0) {
            if (stop.delay() == 0L) {
                stopSounds(engine, player, sounds);
                cleanup.run();
                return List.of();
            }
            List<BukkitTask> tasks = new ArrayList<>(1);
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                stopSounds(engine, player, sounds);
                cleanup.run();
            }, stop.delay()));
            return tasks;
        }

        List<BukkitTask> tasks = new ArrayList<>(fade.plays() + 1);
        try {
            for (int play = 1; play <= fade.plays(); play++) {
                int currentPlay = play;
                long offset = saturatedMultiply(fade.period(), play - 1L);
                long delay = saturatedAdd(stop.delay(), offset);
                tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) return;
                    float multiplier = settings.fadeOutMultiplier(currentPlay);
                    if (multiplier > 0.0001f) {
                        engine.playRuleScaled(loop, player, player.getLocation(), multiplier);
                    }
                }, delay));
            }
            long latestSoundDelay = SoundSpec.fromRule(loop).stream().mapToLong(SoundSpec::delay).max().orElse(0L);
            long finalDelay = saturatedAdd(stop.delay(), saturatedMultiply(fade.period(), fade.plays()));
            finalDelay = saturatedAdd(finalDelay, latestSoundDelay);
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                stopSounds(engine, player, sounds);
                cleanup.run();
            }, finalDelay));
            return tasks;
        } catch (RuntimeException | LinkageError ex) {
            for (BukkitTask task : tasks) task.cancel();
            stopSounds(engine, player, sounds);
            cleanup.run();
            plugin.getLogger().warning("Could not schedule loop fade-out for " + player.getName() + ": " + ex.getMessage());
            return List.of();
        }
    }

    static void cancel(List<BukkitTask> tasks) {
        if (tasks == null) return;
        for (BukkitTask task : tasks) task.cancel();
    }

    private static void stopSounds(SoundEngine engine, Player player, List<String> sounds) {
        if (!player.isOnline()) return;
        for (String sound : sounds) engine.stop(player, sound);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }
}
