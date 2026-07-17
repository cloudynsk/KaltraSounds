package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

final class SoundEngine {
    private final JavaPlugin plugin;
    private final ConfigHub configs;
    private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();
    private final Set<BukkitTask> scheduledTasks = ConcurrentHashMap.newKeySet();

    SoundEngine(JavaPlugin plugin, ConfigHub configs) {
        this.plugin = plugin;
        this.configs = configs;
    }

    boolean playEvent(String eventName, Player actor, Location origin) {
        ConfigurationSection rule = configs.eventRule(eventName);
        if (rule == null || !rule.getBoolean("Enabled", false)) return false;
        return playRule(rule, actor, origin);
    }

    boolean playRule(ConfigurationSection rule, Player actor, Location origin) {
        return playRuleTo(rule, actor, origin, null);
    }

    boolean playRuleTo(ConfigurationSection rule, Player actor, Location origin, Collection<? extends Player> allowedRecipients) {
        return playRuleScaledTo(rule, actor, origin, allowedRecipients, 1.0f);
    }

    boolean playRuleScaled(ConfigurationSection rule, Player actor, Location origin, float volumeMultiplier) {
        return playRuleScaledTo(rule, actor, origin, null, volumeMultiplier);
    }

    private boolean playRuleScaledTo(ConfigurationSection rule, Player actor, Location origin,
                                     Collection<? extends Player> allowedRecipients, float volumeMultiplier) {
        if (!Float.isFinite(volumeMultiplier) || volumeMultiplier < 0.0f) return false;
        List<SoundSpec> specs = SoundSpec.fromRule(rule);
        if (specs.isEmpty()) return false;
        boolean dispatched = false;
        for (SoundSpec spec : specs) dispatched |= play(spec.scaledVolume(volumeMultiplier), actor, origin, allowedRecipients);
        return dispatched;
    }

    boolean play(SoundSpec spec, Player actor, Location origin) {
        return play(spec, actor, origin, null);
    }

    private boolean play(SoundSpec spec, Player actor, Location origin, Collection<? extends Player> allowedRecipients) {
        if (spec == null || resolveSoundKey(spec.key()) == null
                || !Float.isFinite(spec.volume()) || spec.volume() < 0.0f
                || !Float.isFinite(spec.pitch()) || spec.pitch() <= 0.0f
                || !Double.isFinite(spec.radius())
                || (spec.radius() < 0.0 && spec.radius() != -1.0 && spec.radius() != -2.0)) return false;
        if (actor != null && !spec.triggerPermission().isBlank() && !actor.hasPermission(spec.triggerPermission())) return false;
        Location base = origin != null ? origin : actor != null ? actor.getLocation() : null;
        if (base == null) return false;

        Location snapshotOrigin = base.clone();
        List<Player> resolvedRecipients = recipients(spec.radius(), actor, snapshotOrigin);
        if (allowedRecipients != null) {
            Set<UUID> allowed = allowedRecipients.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Player::getUniqueId)
                    .collect(java.util.stream.Collectors.toSet());
            resolvedRecipients.removeIf(player -> !allowed.contains(player.getUniqueId()));
        }
        List<UUID> listenerSnapshot = resolvedRecipients.stream().map(Player::getUniqueId).toList();
        if (listenerSnapshot.isEmpty()) return false;
        UUID reservationOwner = spec.delay() > 0 && actor != null ? actor.getUniqueId() : null;
        if (reservationOwner != null) {
            int max = Math.max(1, configs.config().getInt("performance.max-scheduled-sounds-per-player", 128));
            int count = pending.merge(reservationOwner, 1, Integer::sum);
            if (count > max) {
                releaseReservation(reservationOwner);
                return false;
            }
        }

        Runnable task = () -> {
            try {
                for (UUID listenerId : listenerSnapshot) {
                    Player recipient = Bukkit.getPlayer(listenerId);
                    if (recipient == null || !recipient.isOnline()) continue;
                    if (!spec.listenPermission().isBlank() && !recipient.hasPermission(spec.listenPermission())) continue;
                    if (!spec.ignoresDisabled() && !configs.soundsEnabled(recipient.getUniqueId())) continue;
                    invokePlay(recipient, snapshotOrigin, spec.key(), spec.category(), spec.volume(), spec.pitch());
                    if (configs.debug()) plugin.getLogger().info("Played " + spec.key() + " to " + recipient.getName());
                }
            } finally {
                if (reservationOwner != null) releaseReservation(reservationOwner);
            }
        };

        if (spec.delay() <= 0) {
            task.run();
            return true;
        }

        AtomicReference<BukkitTask> handle = new AtomicReference<>();
        Runnable trackedTask = () -> {
            try {
                task.run();
            } finally {
                BukkitTask completed = handle.get();
                if (completed != null) scheduledTasks.remove(completed);
            }
        };
        try {
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, trackedTask, spec.delay());
            handle.set(scheduled);
            scheduledTasks.add(scheduled);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            if (reservationOwner != null) releaseReservation(reservationOwner);
            plugin.getLogger().warning("Could not schedule delayed sound '" + spec.key() + "': " + ex.getMessage());
            return false;
        }
    }

    boolean playDirect(Player recipient, String key, float volume, float pitch) {
        if (recipient == null || !Float.isFinite(volume) || volume < 0.0f || !Float.isFinite(pitch) || pitch <= 0.0f) return false;
        return invokePlay(recipient, recipient.getLocation(), key, "MASTER", volume, pitch);
    }

    void playDirect(Collection<? extends Player> recipients, Location origin, String key, float volume, float pitch) {
        if (origin == null || !Float.isFinite(volume) || volume < 0.0f || !Float.isFinite(pitch) || pitch <= 0.0f) return;
        for (Player player : recipients) invokePlay(player, origin, key, "MASTER", volume, pitch);
    }

    boolean stop(Player player, String key) {
        String normalized = resolveSoundKey(key == null ? "" : key);
        if (player == null || normalized == null || normalized.isBlank()) return false;
        try {
            Method stop = player.getClass().getMethod("stopSound", String.class);
            stop.invoke(player, normalized);
            return true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Paper 26.1.1 provides stopSound(String). Older runtimes without it cannot stop one named sound.
            return false;
        }
    }

    void stopAround(Location origin, double radius, String key) {
        if (origin == null || !Double.isFinite(radius)) return;
        for (Player player : recipients(radius, null, origin)) stop(player, key);
    }

    void stopAll(Player player) {
        try {
            player.getClass().getMethod("stopAllSounds").invoke(player);
        } catch (ReflectiveOperationException ignored) {
        }
    }


    void clearPending() {
        for (BukkitTask task : scheduledTasks) task.cancel();
        scheduledTasks.clear();
        pending.clear();
    }

    private void releaseReservation(UUID owner) {
        pending.computeIfPresent(owner, (id, count) -> count <= 1 ? null : count - 1);
    }
    private List<Player> recipients(double radius, Player actor, Location origin) {
        List<Player> out = new ArrayList<>();
        if (radius == 0) {
            if (actor != null) out.add(actor);
            return out;
        }
        if (radius == -1) {
            out.addAll(Bukkit.getOnlinePlayers());
            return out;
        }
        World world = origin.getWorld();
        if (world == null) return out;
        if (radius == -2) {
            out.addAll(world.getPlayers());
            return out;
        }
        double max = radius * radius;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(origin) <= max) out.add(player);
        }
        return out;
    }

    private boolean invokePlay(Player player, Location location, String key, String category, float volume, float pitch) {
        String raw = key == null ? "" : key.trim();
        if (raw.isEmpty()) return false;
        String namespaced = resolveSoundKey(raw);
        if (namespaced == null || namespaced.isBlank()) return false;

        // Since Paper 1.21.11 / 26.x, org.bukkit.Sound is an interface rather than a Java enum.
        // The String overload works for both vanilla and custom namespaced sounds and avoids linking
        // the plugin to the changing Sound registry representation.
        try {
            Class<?> categoryClass = Class.forName("org.bukkit.SoundCategory");
            Object soundCategory = namedConstant(categoryClass, category == null ? "MASTER" : category);
            if (soundCategory != null) {
                Method method = player.getClass().getMethod(
                        "playSound", Location.class, String.class, categoryClass, float.class, float.class);
                method.invoke(player, location, namespaced, soundCategory, volume, pitch);
                return true;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Fall through to the category-less String API, which is present across supported Paper versions.
        }

        try {
            player.playSound(location, namespaced, volume, pitch);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().warning("Could not play sound '" + raw + "' resolved as '" + namespaced + "': " + ex.getMessage());
            return false;
        }
    }

    private static Object namedConstant(Class<?> type, String name) {
        String normalized = name == null || name.isBlank() ? "MASTER" : name.trim().toUpperCase(Locale.ROOT);
        try {
            return type.getField(normalized).get(null);
        } catch (ReflectiveOperationException ignored) {
        }
        if (type.isEnum()) {
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object value = Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), normalized);
                return value;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    static String resolveSoundKey(String key) {
        String trimmed = key == null ? "" : key.trim();
        if (trimmed.isEmpty()) return null;

        // Already namespaced or already written as a resource-pack/vanilla dotted key.
        if (trimmed.indexOf(':') >= 0 || trimmed.indexOf('.') >= 0) {
            return trimmed.toLowerCase(Locale.ROOT);
        }

        // Legacy PlayMoreSounds configs use Bukkit constant names such as
        // ENTITY_EXPERIENCE_ORB_PICKUP. Their registry keys cannot be obtained by
        // blindly replacing every underscore with a dot, because some underscores
        // are part of path segments (experience_orb, firework_rocket, etc.).
        String constant = trimmed.toUpperCase(Locale.ROOT);
        try {
            Class<?> soundClass = Class.forName("org.bukkit.Sound");
            Object sound = null;
            try {
                Field field = soundClass.getField(constant);
                if (Modifier.isStatic(field.getModifiers())) sound = field.get(null);
            } catch (NoSuchFieldException ignored) {
                try {
                    sound = soundClass.getMethod("valueOf", String.class).invoke(null, constant);
                } catch (ReflectiveOperationException ignoredAgain) {
                }
            }
            if (sound != null) {
                try {
                    Object namespacedKey = soundClass.getMethod("getKey").invoke(sound);
                    if (namespacedKey != null) return namespacedKey.toString();
                } catch (ReflectiveOperationException ignored) {
                    try {
                        Object adventureKey = soundClass.getMethod("key").invoke(sound);
                        if (adventureKey != null) return adventureKey.toString();
                    } catch (ReflectiveOperationException ignoredAgain) {
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }

        // Bare unknown names are almost always typos. Custom resource-pack sounds
        // must use a namespaced or dotted key, both of which are handled above.
        return null;
    }
}
