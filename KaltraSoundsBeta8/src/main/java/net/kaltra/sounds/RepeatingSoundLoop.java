package net.kaltra.sounds;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class RepeatingSoundLoop {
    private final JavaPlugin plugin;
    private final SoundEngine engine;
    private final Player player;
    private final ConfigurationSection rule;
    private final LoopSettings settings;
    private final BooleanSupplier activeCheck;
    private final Consumer<RepeatingSoundLoop> onFinished;
    private final List<String> soundKeys;
    private BukkitTask task;
    private int plays;
    private boolean cancelled;

    RepeatingSoundLoop(JavaPlugin plugin, SoundEngine engine, Player player, ConfigurationSection rule,
                       BooleanSupplier activeCheck, Consumer<RepeatingSoundLoop> onFinished) {
        this.plugin = plugin;
        this.engine = engine;
        this.player = player;
        this.rule = rule;
        this.settings = LoopSettings.from(rule);
        this.activeCheck = activeCheck;
        this.onFinished = onFinished;
        this.soundKeys = SoundSpec.fromRule(rule).stream().map(SoundSpec::key).toList();
    }

    boolean valid() {
        return !soundKeys.isEmpty();
    }

    List<String> soundKeys() {
        return soundKeys;
    }

    LoopSettings settings() {
        return settings;
    }

    void start() {
        if (!valid() || cancelled) return;
        schedule(settings.delay());
    }

    void cancel(boolean stopSounds) {
        if (cancelled) return;
        cancelled = true;
        if (task != null) task.cancel();
        task = null;
        if (stopSounds && player.isOnline()) {
            for (String sound : soundKeys) engine.stop(player, sound);
        }
    }

    private void schedule(long delay) {
        try {
            task = plugin.getServer().getScheduler().runTaskLater(plugin, this::runCycle, Math.max(0L, delay));
        } catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().warning("Could not schedule loop for " + player.getName() + ": " + ex.getMessage());
            finishNaturally();
        }
    }

    private void runCycle() {
        task = null;
        if (cancelled) return;
        if (!player.isOnline() || !activeCheck.getAsBoolean()) {
            finishNaturally();
            return;
        }

        plays++;
        Location origin = player.getLocation();
        engine.playRuleScaled(rule, player, origin, settings.fadeInMultiplier(plays));

        if (settings.maximumPlays() > 0 && plays >= settings.maximumPlays()) {
            finishNaturally();
            return;
        }
        schedule(settings.nextPeriod());
    }

    private void finishNaturally() {
        if (cancelled) return;
        cancelled = true;
        if (task != null) task.cancel();
        task = null;
        onFinished.accept(this);
    }
}
