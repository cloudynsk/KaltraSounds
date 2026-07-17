package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SituationalModule {
    private static final List<String> FILTERS = List.of("Contains", "Contains SubString", "Ends With", "Equals Exactly", "Equals Ignore Case", "Starts With", "Regex");

    private final JavaPlugin plugin;
    private final ConfigHub configs;
    private final SoundEngine engine;
    private final Map<UUID, String> lastBiome = new HashMap<>();
    private final Map<UUID, Map<String, RepeatingSoundLoop>> biomeLoops = new HashMap<>();
    private final Map<UUID, Map<String, List<BukkitTask>>> biomeStopTasks = new HashMap<>();
    private final Map<String, Long> lastWorldTime = new HashMap<>();
    private BukkitTask locationTask;
    private BukkitTask timeTask;

    SituationalModule(JavaPlugin plugin, ConfigHub configs, SoundEngine engine) {
        this.plugin = plugin;
        this.configs = configs;
        this.engine = engine;
    }

    void start() {
        stop();
        if (!configs.module("situational-sounds")) return;
        for (Player player : Bukkit.getOnlinePlayers()) syncPlayer(player, false);
        for (World world : Bukkit.getWorlds()) {
            lastWorldTime.put(world.getName(), Math.floorMod(world.getTime(), 24000L));
        }
        locationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickLocations, configs.locationInterval(), configs.locationInterval());
        timeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWorldTime, configs.worldTimeInterval(), configs.worldTimeInterval());
    }

    void stop() {
        if (locationTask != null) locationTask.cancel();
        if (timeTask != null) timeTask.cancel();
        locationTask = null;
        timeTask = null;
        for (Map<String, RepeatingSoundLoop> loops : biomeLoops.values()) {
            for (RepeatingSoundLoop loop : loops.values()) loop.cancel(true);
        }
        for (Map<String, List<BukkitTask>> tasks : biomeStopTasks.values()) {
            for (List<BukkitTask> group : tasks.values()) LoopExitController.cancel(group);
        }
        biomeLoops.clear();
        biomeStopTasks.clear();
        lastBiome.clear();
        lastWorldTime.clear();
    }

    boolean onCommand(Player player, String command, boolean cancelled) {
        if (!configs.module("command-sounds")) return false;
        boolean preventDefault = playTextFilters(configs.situational("commands.yml"), command, player, player.getLocation(), cancelled, null);
        if (!preventDefault) playDefaultEvent("Send Command", player, player.getLocation(), cancelled);
        return preventDefault;
    }

    boolean onChat(Player player, String message, boolean cancelled) {
        return onChat(player, message, cancelled, null);
    }

    boolean onChat(Player player, String message, boolean cancelled, java.util.Collection<? extends Player> recipients) {
        if (!configs.module("chat-sounds")) return false;
        boolean preventDefault = playTextFilters(configs.situational("chat sounds.yml"), message, player, player.getLocation(), cancelled, recipients);
        if (!preventDefault) playDefaultEventTo("Player Chat", player, player.getLocation(), cancelled, recipients);
        return preventDefault;
    }

    private boolean playTextFilters(YamlConfiguration yaml, String text, Player player, Location location, boolean cancelled,
                                    java.util.Collection<? extends Player> recipients) {
        if (yaml == null) return false;
        boolean preventDefault = false;
        boolean stopOther = false;
        outer:
        for (String filter : FILTERS) {
            ConfigurationSection group = getSectionIgnoreCase(yaml, filter);
            if (group == null) continue;
            for (String needle : group.getKeys(false)) {
                ConfigurationSection rule = group.getConfigurationSection(needle);
                if (rule == null || !rule.getBoolean("Enabled", false)) continue;
                if (cancelled && rule.getBoolean("Cancellable", false)) continue;
                if (!Criteria.matchesFilter(filter, needle, text)) continue;
                engine.playRuleTo(rule, player, location, recipients);
                ConfigurationSection prevent = rule.getConfigurationSection("Prevent Other Sounds");
                if (prevent != null) {
                    preventDefault |= prevent.getBoolean("Default Sound", false);
                    stopOther |= prevent.getBoolean("Other Filters", false) || prevent.getBoolean("Other Criteria", false);
                }
                if (stopOther) break outer;
            }
        }
        return preventDefault;
    }

    boolean onItemHeld(Player player, ItemStack stack, boolean cancelled) {
        return playItemRules("items held.yml", stack, player, cancelled, "Change Held Item");
    }

    boolean onItemClicked(Player player, ItemStack stack, boolean cancelled) {
        return playItemRules("items clicked.yml", stack, player, cancelled, "Inventory Click");
    }

    boolean onItemSwung(Player player, ItemStack stack) {
        return playItemRules("items swung.yml", stack, player, false, "Player Swing");
    }

    private boolean playItemRules(String file, ItemStack stack, Player player, boolean cancelled, String defaultEvent) {
        YamlConfiguration yaml = configs.situational(file);
        String material = stack == null || stack.getType() == null ? "AIR" : stack.getType().name();
        boolean preventDefault = false;
        boolean stopOther = false;
        if (yaml != null) {
            for (String criterion : yaml.getKeys(false)) {
                if (criterion.equalsIgnoreCase("Version")) continue;
                ConfigurationSection rule = yaml.getConfigurationSection(criterion);
                if (rule == null || !rule.getBoolean("Enabled", false)) continue;
                if (cancelled && rule.getBoolean("Cancellable", false)) continue;
                if (!Criteria.matches(criterion, material)) continue;
                engine.playRule(rule, player, player.getLocation());
                ConfigurationSection prevent = rule.getConfigurationSection("Prevent Other Sounds");
                if (prevent != null) {
                    preventDefault |= prevent.getBoolean("Default Sound", false);
                    stopOther |= prevent.getBoolean("Other Criteria", false);
                }
                if (stopOther) break;
            }
        }
        if (!preventDefault) playDefaultEvent(defaultEvent, player, player.getLocation(), cancelled);
        return preventDefault;
    }

    boolean onDeath(Player player, String cause) {
        YamlConfiguration yaml = configs.situational("death types.yml");
        ConfigurationSection rule = yaml == null ? null : getSectionIgnoreCase(yaml, cause);
        boolean prevent = false;
        if (rule != null && rule.getBoolean("Enabled", false)) {
            engine.playRule(rule, player, player.getLocation());
            prevent = rule.getBoolean("Prevent Default Sound", false);
        }
        if (!prevent) engine.playEvent("Player Death", player, player.getLocation());
        return prevent;
    }

    boolean onGameMode(Player player, String mode, boolean cancelled) {
        YamlConfiguration yaml = configs.situational("game modes.yml");
        ConfigurationSection rule = yaml == null ? null : getSectionIgnoreCase(yaml, mode);
        boolean prevent = false;
        if (rule != null && rule.getBoolean("Enabled", false) && !(cancelled && rule.getBoolean("Cancellable", false))) {
            engine.playRule(rule, player, player.getLocation());
            prevent = rule.getBoolean("Prevent Default Sound", false);
        }
        if (!prevent) playDefaultEvent("Game Mode Change", player, player.getLocation(), cancelled);
        return prevent;
    }

    boolean onHit(Entity damager, Entity victim, ItemStack held, boolean cancelled) {
        YamlConfiguration yaml = configs.situational("hit sounds.yml");
        boolean prevent = false;
        boolean stop = false;
        if (yaml != null) {
            String damagerType = damager == null ? "UNKNOWN" : damager.getType().name();
            String victimType = victim == null ? "UNKNOWN" : victim.getType().name();
            String itemType = held == null || held.getType() == null ? "AIR" : held.getType().name();
            Player actor = damager instanceof Player p ? p : null;
            Location location = victim == null ? actor == null ? null : actor.getLocation() : victim.getLocation();
            for (String expression : yaml.getKeys(false)) {
                if (expression.equalsIgnoreCase("Version")) continue;
                String lower = expression.toLowerCase(Locale.ROOT);
                int hit = lower.indexOf(" hit ");
                int holding = lower.indexOf(" holding ");
                if (hit < 0 || holding < hit) continue;
                String a = expression.substring(0, hit).trim();
                String b = expression.substring(hit + 5, holding).trim();
                String c = expression.substring(holding + 9).trim();
                if (!Criteria.matches(a, damagerType) || !Criteria.matches(b, victimType) || !Criteria.matches(c, itemType)) continue;
                ConfigurationSection rule = yaml.getConfigurationSection(expression);
                if (rule == null || !rule.getBoolean("Enabled", false)) continue;
                if (cancelled && rule.getBoolean("Cancellable", false)) continue;
                engine.playRule(rule, actor, location);
                ConfigurationSection other = rule.getConfigurationSection("Prevent Other Sounds");
                if (other != null) {
                    prevent |= other.getBoolean("Default Sound", false);
                    stop |= other.getBoolean("Other Criteria", false);
                }
                if (stop) break;
            }
        }
        Player actor = damager instanceof Player p ? p : null;
        Location origin = victim == null ? actor == null ? null : actor.getLocation() : victim.getLocation();
        if (!prevent) playDefaultEvent("Entity Hit", actor, origin, cancelled);
        return prevent;
    }


    void syncPlayer(Player player, boolean transitions) {
        if (player == null || !player.isOnline() || !configs.module("situational-sounds")) return;
        YamlConfiguration biomes = configs.situational("biomes.yml");
        if (biomes == null) return;
        Location location = player.getLocation();
        String biome;
        try {
            biome = location.getBlock().getBiome().name();
        } catch (RuntimeException ignored) {
            return;
        }
        String token = player.getWorld().getName() + ":" + biome;
        String previous = lastBiome.put(player.getUniqueId(), token);
        if (previous == null) {
            if (transitions) enterBiome(player, biomes, player.getWorld().getName(), biome);
            else {
                ConfigurationSection biomeSection = nestedIgnoreCase(biomes, player.getWorld().getName(), biome);
                ConfigurationSection loop = getSectionIgnoreCase(biomeSection, "Loop");
                if (loop != null && loop.getBoolean("Enabled", false)) startBiomeLoop(player, token, loop);
            }
        } else if (!previous.equals(token)) {
            String[] split = previous.split(":", 2);
            if (split.length == 2) leaveBiome(player, biomes, split[0], split[1]);
            enterBiome(player, biomes, player.getWorld().getName(), biome);
        }
    }

    void onQuit(Player player) {
        if (player != null) clearPlayerState(player.getUniqueId());
    }

    private void clearPlayerState(UUID id) {
        Map<String, RepeatingSoundLoop> loops = biomeLoops.remove(id);
        if (loops != null) for (RepeatingSoundLoop loop : loops.values()) loop.cancel(false);
        Map<String, List<BukkitTask>> stops = biomeStopTasks.remove(id);
        if (stops != null) for (List<BukkitTask> group : stops.values()) LoopExitController.cancel(group);
        lastBiome.remove(id);
    }

    private boolean playDefaultEvent(String event, Player actor, Location origin, boolean cancelled) {
        ConfigurationSection rule = configs.eventRule(event);
        if (rule == null || !rule.getBoolean("Enabled", false)) return false;
        if (cancelled && rule.getBoolean("Cancellable", false)) return false;
        return engine.playRule(rule, actor, origin);
    }

    private boolean playDefaultEventTo(String event, Player actor, Location origin, boolean cancelled,
                                       java.util.Collection<? extends Player> recipients) {
        ConfigurationSection rule = configs.eventRule(event);
        if (rule == null || !rule.getBoolean("Enabled", false)) return false;
        if (cancelled && rule.getBoolean("Cancellable", false)) return false;
        return engine.playRuleTo(rule, actor, origin, recipients);
    }
    private void tickLocations() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            syncPlayer(player, true);
        }
        Set<UUID> stale = new HashSet<>(lastBiome.keySet());
        stale.addAll(biomeLoops.keySet());
        stale.removeAll(online);
        for (UUID id : stale) clearPlayerState(id);
    }

    private void enterBiome(Player player, YamlConfiguration yaml, String world, String biome) {
        String key = world + ":" + biome;
        cancelPendingBiomeStops(player, key);
        ConfigurationSection biomeSection = nestedIgnoreCase(yaml, world, biome);
        if (biomeSection == null) return;
        ConfigurationSection enter = getSectionIgnoreCase(biomeSection, "Enter");
        if (enter != null) engine.playRule(enter, player, player.getLocation());
        ConfigurationSection loop = getSectionIgnoreCase(biomeSection, "Loop");
        if (loop != null && loop.getBoolean("Enabled", false)) startBiomeLoop(player, key, loop);
    }

    private void leaveBiome(Player player, YamlConfiguration yaml, String world, String biome) {
        String key = world + ":" + biome;
        ConfigurationSection biomeSection = nestedIgnoreCase(yaml, world, biome);
        if (biomeSection == null) return;
        ConfigurationSection leave = getSectionIgnoreCase(biomeSection, "Leave");
        if (leave != null) engine.playRule(leave, player, player.getLocation());
        ConfigurationSection loop = getSectionIgnoreCase(biomeSection, "Loop");
        cancelBiomeLoop(player, key);
        if (loop != null) stopConfiguredOnExit(player, key + "|loop", loop);
        ConfigurationSection enter = getSectionIgnoreCase(biomeSection, "Enter");
        if (enter != null) stopConfiguredOnExit(player, key + "|enter", enter);
    }

    private void startBiomeLoop(Player player, String key, ConfigurationSection loop) {
        UUID playerId = player.getUniqueId();
        cancelPendingBiomeStops(player, key);
        cancelBiomeLoop(player, key, true);
        RepeatingSoundLoop playback = new RepeatingSoundLoop(
                plugin, engine, player, loop,
                () -> key.equals(lastBiome.get(playerId)),
                finished -> removeCompletedBiomeLoop(playerId, key, finished)
        );
        if (!playback.valid()) return;
        biomeLoops.computeIfAbsent(playerId, ignored -> new HashMap<>()).put(key, playback);
        playback.start();
    }

    private void cancelBiomeLoop(Player player, String key) {
        cancelBiomeLoop(player, key, false);
    }

    private void cancelBiomeLoop(Player player, String key, boolean stopSounds) {
        UUID playerId = player.getUniqueId();
        Map<String, RepeatingSoundLoop> loops = biomeLoops.get(playerId);
        if (loops == null) return;
        RepeatingSoundLoop playback = loops.remove(key);
        if (playback != null) playback.cancel(stopSounds);
        if (loops.isEmpty()) biomeLoops.remove(playerId);
    }

    private void removeCompletedBiomeLoop(UUID playerId, String key, RepeatingSoundLoop completed) {
        Map<String, RepeatingSoundLoop> loops = biomeLoops.get(playerId);
        if (loops == null || loops.get(key) != completed) return;
        loops.remove(key);
        if (loops.isEmpty()) biomeLoops.remove(playerId);
    }

    private void cancelPendingBiomeStops(Player player, String key) {
        Map<String, List<BukkitTask>> byBiome = biomeStopTasks.get(player.getUniqueId());
        if (byBiome == null) return;
        for (String stopKey : new ArrayList<>(byBiome.keySet())) {
            if (stopKey.equals(key) || stopKey.startsWith(key + "|")) {
                LoopExitController.cancel(byBiome.remove(stopKey));
            }
        }
        if (byBiome.isEmpty()) biomeStopTasks.remove(player.getUniqueId());
    }

    private void stopConfiguredOnExit(Player player, String biomeKey, ConfigurationSection section) {
        List<String> sounds = SoundSpec.fromRule(section).stream().map(SoundSpec::key).toList();
        if (sounds.isEmpty()) return;
        UUID playerId = player.getUniqueId();
        cancelPendingBiomeStops(player, biomeKey);
        List<BukkitTask> tasks = LoopExitController.schedule(plugin, engine, player, section, sounds,
                () -> removeCompletedBiomeStop(playerId, biomeKey));
        if (!tasks.isEmpty()) {
            biomeStopTasks.computeIfAbsent(playerId, ignored -> new HashMap<>()).put(biomeKey, tasks);
        }
    }

    private void removeCompletedBiomeStop(UUID playerId, String key) {
        Map<String, List<BukkitTask>> byBiome = biomeStopTasks.get(playerId);
        if (byBiome == null) return;
        byBiome.remove(key);
        if (byBiome.isEmpty()) biomeStopTasks.remove(playerId);
    }

    private void tickWorldTime() {
        YamlConfiguration yaml = configs.situational("world time triggers.yml");
        if (yaml == null) return;
        for (World world : Bukkit.getWorlds()) {
            ConfigurationSection worldRules = getSectionIgnoreCase(yaml, world.getName());
            if (worldRules == null) continue;
            long current = Math.floorMod(world.getTime(), 24000L);
            long previous = lastWorldTime.getOrDefault(world.getName(), current);
            for (String key : worldRules.getKeys(false)) {
                long trigger;
                try { trigger = Math.floorMod(Long.parseLong(key), 24000L); }
                catch (NumberFormatException ignored) { continue; }
                boolean crossed = previous <= current ? trigger > previous && trigger <= current : trigger > previous || trigger <= current;
                if (crossed) {
                    ConfigurationSection rule = worldRules.getConfigurationSection(key);
                    if (rule != null) engine.playRule(rule, null, world.getSpawnLocation());
                }
            }
            lastWorldTime.put(world.getName(), current);
        }
    }

    private static ConfigurationSection getSectionIgnoreCase(ConfigurationSection parent, String key) {
        if (parent == null || key == null) return null;
        for (String candidate : parent.getKeys(false)) if (candidate.equalsIgnoreCase(key)) return parent.getConfigurationSection(candidate);
        return null;
    }

    private static ConfigurationSection nestedIgnoreCase(ConfigurationSection root, String first, String second) {
        ConfigurationSection one = getSectionIgnoreCase(root, first);
        return getSectionIgnoreCase(one, second);
    }
}
