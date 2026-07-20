package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class RegionModule {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final JavaPlugin plugin;
    private final ConfigHub configs;
    private final SoundEngine engine;
    private final Map<String, NativeRegion> nativeRegions = new LinkedHashMap<>();
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Map<UUID, Set<String>> active = new HashMap<>();
    private final Map<UUID, Map<String, RepeatingSoundLoop>> loops = new HashMap<>();
    private final Map<UUID, Map<String, List<BukkitTask>>> pendingStops = new HashMap<>();
    private final Set<String> warnedIntegrationFailures = new HashSet<>();
    private BukkitTask task;


    RegionModule(JavaPlugin plugin, ConfigHub configs, SoundEngine engine) {
        this.plugin = plugin;
        this.configs = configs;
        this.engine = engine;
    }

    void load() {
        stop();
        nativeRegions.clear();
        warnedIntegrationFailures.clear();
        ConfigurationSection root = configs.regions().getConfigurationSection("regions");
        if (root != null) {
            for (String name : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(name);
                if (sec == null) continue;
                if (!validName(name)) {
                    plugin.getLogger().warning("Skipped native sound region '" + name + "': names may contain only letters, numbers, underscores and hyphens.");
                    continue;
                }
                String world = sec.getString("world", "");
                String owner = sec.getString("owner", "");
                ConfigurationSection min = sec.getConfigurationSection("min");
                ConfigurationSection max = sec.getConfigurationSection("max");
                if (world.isBlank() || min == null || max == null) {
                    plugin.getLogger().warning("Skipped native sound region '" + name + "': missing world or min/max corners.");
                    continue;
                }
                int x1 = min.getInt("x"), y1 = min.getInt("y"), z1 = min.getInt("z");
                int x2 = max.getInt("x"), y2 = max.getInt("y"), z2 = max.getInt("z");
                NativeRegion region = new NativeRegion(name, owner, world,
                        Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                        Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
                String key = name.toLowerCase(Locale.ROOT);
                if (nativeRegions.put(key, region) != null) {
                    plugin.getLogger().warning("Duplicate native sound region name ignoring case: " + name);
                }
            }
        }
        if (configs.module("native-regions") || configs.module("external-regions")) {
            long interval = configs.locationInterval();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
            synchronizeOnlinePlayers(false);
        }
        if (configs.regionLogging()) {
            plugin.getLogger().info("Region engine loaded: " + nativeRegions.size() + " native region(s), check interval " + configs.locationInterval() + " tick(s).");
        }
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Map<String, RepeatingSoundLoop> playerLoops : loops.values()) {
            for (RepeatingSoundLoop loop : playerLoops.values()) loop.cancel(true);
        }
        loops.clear();
        for (Map<String, List<BukkitTask>> map : pendingStops.values()) {
            for (List<BukkitTask> tasks : map.values()) LoopExitController.cancel(tasks);
        }
        pendingStops.clear();
        active.clear();
    }

    void onQuit(Player player) {
        UUID id = player.getUniqueId();
        Set<String> before = new LinkedHashSet<>(active.getOrDefault(id, Collections.emptySet()));
        for (String token : before) leave(player, token);
        cancelPlayerLoops(id, null);
        cancelPendingStops(id, null);
        active.remove(id);
        pos1.remove(id);
        pos2.remove(id);
    }

    Collection<NativeRegion> allNative() { return Collections.unmodifiableCollection(nativeRegions.values()); }

    void setPos1(Player player, Location location) { pos1.put(player.getUniqueId(), location); }
    void setPos2(Player player, Location location) { pos2.put(player.getUniqueId(), location); }

    String setSound(Player player, String regionName, String phase, String sound, double volume, double pitch, Long loopPeriodTicks) {
        NativeRegion region = find(regionName);
        if (region == null) return "&cRegion not found.";
        if (player != null) {
            if (!player.hasPermission("playmoresounds.region.sound") && !player.hasPermission("playmoresounds.admin")) return configs.message("no-permission");
            if (!isOwnerOrAdmin(player, region) && !player.hasPermission("playmoresounds.region.sound.others")) return configs.message("no-permission");
        }
        if (sound == null || sound.isBlank()) return "&cProvide a sound name.";
        if (!Double.isFinite(volume) || volume < 0) return "&cVolume must be a finite non-negative number.";
        if (!Double.isFinite(pitch) || pitch <= 0) return "&cPitch must be a finite number greater than zero.";
        String normalizedPhase = switch (phase.toLowerCase(Locale.ROOT)) {
            case "enter" -> "Enter";
            case "leave", "exit" -> "Leave";
            case "loop" -> "Loop";
            default -> null;
        };
        if (normalizedPhase == null) return "&cPhase must be enter, leave, or loop.";
        if (normalizedPhase.equals("Loop") && (loopPeriodTicks == null || loopPeriodTicks < 1L)) {
            return "&cLoop sounds require a positive replay period.";
        }
        YamlConfiguration yaml = configs.situational("regions.yml");
        String provider = providerContainingRegion(region.name());
        if (provider == null) provider = "KaltraSounds";
        String path = provider + "." + region.name() + "." + normalizedPhase;
        yaml.set(path + ".Enabled", true);
        if (normalizedPhase.equals("Loop")) {
            if (!yaml.contains(path + ".Delay")) yaml.set(path + ".Delay", 0);
            yaml.set(path + ".Period", loopPeriodTicks);
            if (!yaml.contains(path + ".Period Randomness")) yaml.set(path + ".Period Randomness", 0);
            if (!yaml.contains(path + ".Maximum Plays")) yaml.set(path + ".Maximum Plays", 0);
            if (!yaml.contains(path + ".Fade In.Enabled")) yaml.set(path + ".Fade In.Enabled", false);
            if (!yaml.contains(path + ".Fade In.Plays")) yaml.set(path + ".Fade In.Plays", 3);
            if (!yaml.contains(path + ".Fade In.Start Volume Multiplier")) yaml.set(path + ".Fade In.Start Volume Multiplier", 0.2);
            if (!yaml.contains(path + ".Fade In.Curve")) yaml.set(path + ".Fade In.Curve", "SMOOTHSTEP");
            if (!yaml.contains(path + ".Stop On Exit.Enabled")) yaml.set(path + ".Stop On Exit.Enabled", true);
            if (!yaml.contains(path + ".Stop On Exit.Delay")) yaml.set(path + ".Stop On Exit.Delay", 0);
            if (!yaml.contains(path + ".Stop On Exit.Fade Out.Enabled")) yaml.set(path + ".Stop On Exit.Fade Out.Enabled", false);
            if (!yaml.contains(path + ".Stop On Exit.Fade Out.Plays")) yaml.set(path + ".Stop On Exit.Fade Out.Plays", 3);
            if (!yaml.contains(path + ".Stop On Exit.Fade Out.Period")) yaml.set(path + ".Stop On Exit.Fade Out.Period", 20);
            if (!yaml.contains(path + ".Stop On Exit.Fade Out.End Volume Multiplier")) yaml.set(path + ".Stop On Exit.Fade Out.End Volume Multiplier", 0.0);
            if (!yaml.contains(path + ".Stop On Exit.Fade Out.Curve")) yaml.set(path + ".Stop On Exit.Fade Out.Curve", "SMOOTHSTEP");
        }
        yaml.set(path + ".Sounds.1.Sound", sound);
        yaml.set(path + ".Sounds.1.Volume", volume);
        yaml.set(path + ".Sounds.1.Pitch", pitch);
        yaml.set(path + ".Sounds.1.Options.Radius", 0.0);
        if (!configs.saveSituational("regions.yml")) return "&cCould not save Sounds/regions.yml; check the server log.";
        restartRegionLoops(region.name());
        String periodMessage = normalizedPhase.equals("Loop")
                ? " &7(replays every &f" + DurationTicks.describe(loopPeriodTicks) + "&7)"
                : "";
        return "&aSet " + normalizedPhase.toLowerCase(Locale.ROOT) + " sound for &f" + region.name() + "&a." + periodMessage;
    }

    boolean handleWand(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!isWand(item) || event.getClickedBlock() == null) return false;
        if (!player.hasPermission("playmoresounds.region.wand") && !player.hasPermission("playmoresounds.admin")) return false;
        Location location = event.getClickedBlock().getLocation();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            setPos1(player, location);
            Text.send(player, configs.prefix(), configs.message("region-pos1"));
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            setPos2(player, location);
            Text.send(player, configs.prefix(), configs.message("region-pos2"));
        } else return false;
        event.setCancelled(true);
        return true;
    }

    ItemStack createWand() {
        Material material = Material.matchMaterial(configs.config().getString("regions.wand.material", "FEATHER"));
        if (material == null || material == Material.AIR) material = Material.FEATHER;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(configs.config().getString("regions.wand.name", "&6&l&nRegion Selection Tool")));
            item.setItemMeta(meta);
        }
        return item;
    }

    boolean isWand(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        return meta.getDisplayName().equals(Text.color(configs.config().getString("regions.wand.name", "&6&l&nRegion Selection Tool")));
    }

    String create(Player player, String name) {
        if (!player.hasPermission("playmoresounds.region.create") && !player.hasPermission("playmoresounds.admin")) return configs.message("no-permission");
        String nameProblem = validateName(name);
        if (nameProblem != null) return nameProblem;
        String key = name.toLowerCase(Locale.ROOT);
        if (nativeRegions.containsKey(key)) return "&cA region with that name already exists.";
        Location a = pos1.get(player.getUniqueId()), b = pos2.get(player.getUniqueId());
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) return configs.message("region-selection-missing");
        int owned = 0;
        for (NativeRegion region : nativeRegions.values()) if (region.owner().equals(player.getUniqueId().toString())) owned++;
        int maxRegions = configs.config().getInt("regions.max-regions-per-player", 5);
        if (owned >= maxRegions && !player.hasPermission("playmoresounds.region.create.unlimited.regions")) return "&cYou already own the maximum number of regions.";
        NativeRegion region = new NativeRegion(name, player.getUniqueId().toString(), a.getWorld().getName(),
                Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));
        long maxArea = configs.config().getLong("regions.max-area", 15625);
        if (region.volume() > maxArea && !player.hasPermission("playmoresounds.region.create.unlimited.area")) return "&cThe selected region is too large: " + region.volume() + " > " + maxArea + ".";
        if (!player.hasPermission("playmoresounds.region.select.overlap")) {
            for (NativeRegion existing : nativeRegions.values()) {
                if (region.intersects(existing)) return "&cThe selection overlaps region &f" + existing.name() + "&c.";
            }
        }
        nativeRegions.put(key, region);
        writeRegion(region);
        if (!configs.saveRegions()) {
            nativeRegions.remove(key);
            configs.regions().set("regions." + region.name(), null);
            return "&cCould not save regions-data.yml; the region was not created.";
        }
        return Text.replace(configs.message("region-created"), Map.of("name", name));
    }

    String remove(Player player, String name) {
        NativeRegion region = nativeRegions.get(name.toLowerCase(Locale.ROOT));
        if (region == null) return "&cRegion not found.";
        if (!player.hasPermission("playmoresounds.region.remove") && !player.hasPermission("playmoresounds.admin")) return configs.message("no-permission");
        if (!isOwnerOrAdmin(player, region) && !player.hasPermission("playmoresounds.region.remove.others")) return configs.message("no-permission");
        deactivateNativeRegion(region.name());
        nativeRegions.remove(name.toLowerCase(Locale.ROOT));
        configs.regions().set("regions." + region.name(), null);
        removeSoundConfig(region.name());
        boolean regionSaved = configs.saveRegions();
        boolean soundsSaved = configs.saveSituational("regions.yml");
        if (!regionSaved || !soundsSaved) return "&cRegion removal changed memory but could not save every file; reload before making more region edits.";
        return Text.replace(configs.message("region-removed"), Map.of("name", region.name()));
    }

    String rename(Player player, String oldName, String newName) {
        NativeRegion region = nativeRegions.get(oldName.toLowerCase(Locale.ROOT));
        if (region == null) return "&cRegion not found.";
        if (!player.hasPermission("playmoresounds.region.rename") && !player.hasPermission("playmoresounds.admin")) return configs.message("no-permission");
        if (!isOwnerOrAdmin(player, region) && !player.hasPermission("playmoresounds.region.rename.others")) return configs.message("no-permission");
        String nameProblem = validateName(newName);
        if (nameProblem != null) return nameProblem;
        if (nativeRegions.containsKey(newName.toLowerCase(Locale.ROOT))) return "&cThe new name is already used.";
        deactivateNativeRegion(region.name());
        configs.regions().set("regions." + region.name(), null);
        nativeRegions.remove(oldName.toLowerCase(Locale.ROOT));
        NativeRegion replacement = new NativeRegion(newName, region.owner(), region.world(), region.minX(), region.minY(), region.minZ(), region.maxX(), region.maxY(), region.maxZ());
        nativeRegions.put(newName.toLowerCase(Locale.ROOT), replacement);
        writeRegion(replacement);
        renameSoundConfig(region.name(), newName);
        boolean regionSaved = configs.saveRegions();
        boolean soundsSaved = configs.saveSituational("regions.yml");
        if (!regionSaved || !soundsSaved) return "&cRegion rename changed memory but could not save every file; reload before making more region edits.";
        synchronizeOnlinePlayers(false);
        return "&aRenamed region to &f" + newName + "&a.";
    }

    NativeRegion find(String name) { return name == null ? null : nativeRegions.get(name.toLowerCase(Locale.ROOT)); }

    List<String> names() { return names(null); }

    List<String> names(Player viewer) {
        List<String> out = new ArrayList<>();
        for (NativeRegion region : nativeRegions.values()) if (canList(viewer, region)) out.add(region.name());
        return out;
    }

    boolean canList(Player viewer, NativeRegion region) {
        return viewer == null || isOwnerOrAdmin(viewer, region)
                || viewer.hasPermission("playmoresounds.region.list.others");
    }

    boolean canViewInfo(Player viewer, NativeRegion region) {
        return viewer == null || isOwnerOrAdmin(viewer, region)
                || viewer.hasPermission("playmoresounds.region.info.others");
    }

    void syncPlayer(Player player, boolean transitions) {
        if (player != null && player.isOnline()) updatePlayer(player, transitions);
    }

    Set<String> regionsAt(Player player) {
        Set<String> out = new LinkedHashSet<>();
        Location location = player.getLocation();
        if (configs.module("native-regions")) {
            for (NativeRegion region : nativeRegions.values()) if (region.contains(location)) out.add(nativeToken(region.name()));
        }
        if (configs.module("external-regions")) {
            if (configs.integration("worldguard")) for (String name : worldGuardRegions(location)) out.add("WorldGuard:" + name);
            if (configs.integration("redprotect")) for (String name : redProtectRegions(location)) out.add("RedProtect:" + name);
        }
        return out;
    }

    private void tick() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            updatePlayer(player, true);
        }
        Set<UUID> stale = new HashSet<>(active.keySet());
        stale.addAll(loops.keySet());
        stale.addAll(pendingStops.keySet());
        stale.removeAll(online);
        for (UUID id : stale) {
            cancelPlayerLoops(id, null);
            cancelPendingStops(id, null);
            active.remove(id);
        }
    }

    private void synchronizeOnlinePlayers(boolean transitions) {
        for (Player player : Bukkit.getOnlinePlayers()) updatePlayer(player, transitions);
    }

    private void updatePlayer(Player player, boolean transitions) {
        Set<String> now = regionsAt(player);
        Set<String> before = active.getOrDefault(player.getUniqueId(), Collections.emptySet());
        if (transitions) {
            for (String token : now) if (!before.contains(token)) enter(player, token);
            for (String token : before) if (!now.contains(token)) leave(player, token);
        } else {
            cancelPlayerLoops(player.getUniqueId(), player);
            for (String token : now) startConfiguredLoop(player, token);
        }
        active.put(player.getUniqueId(), now);
    }

    private void enter(Player player, String token) {
        cancelPendingStops(player.getUniqueId(), token);
        ConfigurationSection region = findSoundRegion(token);
        boolean playDefault = true;
        boolean playEnter = true;
        if (region != null) {
            ConfigurationSection loop = sectionIgnoreCase(region, "Loop");
            if (loop != null && loop.getBoolean("Enabled", false)) {
                startLoop(player, token, loop);
                if (loop.getBoolean("Prevent Default Sound", false)) playDefault = false;
                ConfigurationSection prevent = sectionIgnoreCase(loop, "Prevent Other Sounds");
                if (prevent != null) {
                    if (prevent.getBoolean("Enter Sound", false)) playEnter = false;
                    if (prevent.getBoolean("Default Sound", false)) playDefault = false;
                }
                if (loop.getBoolean("Prevent Enter Sound", false)) playEnter = false;
            }
            ConfigurationSection enter = sectionIgnoreCase(region, "Enter");
            if (playEnter && enter != null && enter.getBoolean("Enabled", false)) {
                engine.playRule(enter, player, player.getLocation());
                if (enter.getBoolean("Prevent Default Sound", false)) playDefault = false;
            }
        }
        if (playDefault) engine.playEvent("Region Enter", player, player.getLocation());
        logTransition(player, "entered", token);
    }

    private void leave(Player player, String token) {
        ConfigurationSection region = findSoundRegion(token);
        boolean playDefault = true;
        ConfigurationSection loop = region == null ? null : sectionIgnoreCase(region, "Loop");
        stopLoop(player, token, loop, true);
        if (region != null) {
            ConfigurationSection enter = sectionIgnoreCase(region, "Enter");
            scheduleStopOnExit(player, token + "|enter", enter, soundsOf(enter));
            ConfigurationSection leave = sectionIgnoreCase(region, "Leave");
            if (leave != null && leave.getBoolean("Enabled", false)) {
                engine.playRule(leave, player, player.getLocation());
                if (leave.getBoolean("Prevent Default Sound", false)) playDefault = false;
            }
        }
        if (playDefault) engine.playEvent("Region Leave", player, player.getLocation());
        logTransition(player, "left", token);
    }

    private void startConfiguredLoop(Player player, String token) {
        ConfigurationSection region = findSoundRegion(token);
        ConfigurationSection loop = region == null ? null : sectionIgnoreCase(region, "Loop");
        if (loop != null && loop.getBoolean("Enabled", false)) startLoop(player, token, loop);
    }

    private void startLoop(Player player, String token, ConfigurationSection loop) {
        UUID playerId = player.getUniqueId();
        cancelPendingStops(playerId, token);
        cancelLoop(playerId, token, player);
        RepeatingSoundLoop playback = new RepeatingSoundLoop(
                plugin, engine, player, loop,
                () -> {
                    Set<String> current = active.get(playerId);
                    return current != null && current.contains(token);
                },
                finished -> removeCompletedLoop(playerId, token, finished)
        );
        if (!playback.valid()) return;
        loops.computeIfAbsent(playerId, ignored -> new HashMap<>()).put(token, playback);
        playback.start();
    }

    private void stopLoop(Player player, String token, ConfigurationSection loop, boolean applyStopOnExit) {
        RepeatingSoundLoop playback = removeLoop(player.getUniqueId(), token);
        if (playback != null) playback.cancel(false);
        List<String> sounds = playback == null ? soundsOf(loop) : playback.soundKeys();
        if (applyStopOnExit) scheduleStopOnExit(player, token + "|loop", loop, sounds);
    }

    private RepeatingSoundLoop removeLoop(UUID playerId, String token) {
        Map<String, RepeatingSoundLoop> map = loops.get(playerId);
        if (map == null) return null;
        RepeatingSoundLoop playback = map.remove(token);
        if (map.isEmpty()) loops.remove(playerId);
        return playback;
    }

    private void removeCompletedLoop(UUID playerId, String token, RepeatingSoundLoop completed) {
        Map<String, RepeatingSoundLoop> map = loops.get(playerId);
        if (map == null || map.get(token) != completed) return;
        map.remove(token);
        if (map.isEmpty()) loops.remove(playerId);
    }

    private void cancelLoop(UUID playerId, String token, Player player) {
        RepeatingSoundLoop playback = removeLoop(playerId, token);
        if (playback != null) playback.cancel(player != null);
    }

    private void scheduleStopOnExit(Player player, String stopKey, ConfigurationSection source, List<String> sounds) {
        if (source == null || sounds.isEmpty()) return;
        UUID playerId = player.getUniqueId();
        cancelPendingStop(playerId, stopKey);
        List<BukkitTask> tasks = LoopExitController.schedule(plugin, engine, player, source, sounds,
                () -> removeCompletedStop(playerId, stopKey));
        if (!tasks.isEmpty()) {
            pendingStops.computeIfAbsent(playerId, ignored -> new HashMap<>()).put(stopKey, tasks);
        }
    }

    private void removeCompletedStop(UUID playerId, String key) {
        Map<String, List<BukkitTask>> map = pendingStops.get(playerId);
        if (map == null) return;
        map.remove(key);
        if (map.isEmpty()) pendingStops.remove(playerId);
    }

    private void cancelPendingStop(UUID playerId, String key) {
        Map<String, List<BukkitTask>> map = pendingStops.get(playerId);
        if (map == null) return;
        LoopExitController.cancel(map.remove(key));
        if (map.isEmpty()) pendingStops.remove(playerId);
    }

    private void cancelPendingStops(UUID playerId, String tokenPrefix) {
        Map<String, List<BukkitTask>> map = pendingStops.get(playerId);
        if (map == null) return;
        for (String key : new ArrayList<>(map.keySet())) {
            if (tokenPrefix == null || key.startsWith(tokenPrefix + "|")) {
                LoopExitController.cancel(map.remove(key));
            }
        }
        if (map.isEmpty()) pendingStops.remove(playerId);
    }

    private void cancelPlayerLoops(UUID playerId, Player player) {
        Map<String, RepeatingSoundLoop> map = loops.remove(playerId);
        if (map == null) return;
        for (RepeatingSoundLoop playback : map.values()) playback.cancel(player != null);
    }

    private List<String> soundsOf(ConfigurationSection source) {
        List<String> out = new ArrayList<>();
        for (SoundSpec spec : SoundSpec.fromRule(source)) out.add(spec.key());
        return out;
    }

    private ConfigurationSection findSoundRegion(String token) {
        int colon = token.indexOf(':');
        if (colon < 0) return null;
        String provider = token.substring(0, colon), name = token.substring(colon + 1);
        return providerRegion(provider, name);
    }

    private ConfigurationSection providerRegion(String provider, String name) {
        YamlConfiguration yaml = configs.situational("regions.yml");
        ConfigurationSection providers = sectionIgnoreCase(yaml, provider);
        return sectionIgnoreCase(providers, name);
    }

    private String nativeToken(String name) {
        String provider = providerContainingRegion(name);
        return (provider == null ? "KaltraSounds" : provider) + ":" + name;
    }

    private String providerContainingRegion(String name) {
        if (providerRegion("KaltraSounds", name) != null) return "KaltraSounds";
        if (providerRegion("PlayMoreSounds", name) != null) return "PlayMoreSounds";
        return null;
    }

    private void writeRegion(NativeRegion region) {
        String path = "regions." + region.name();
        YamlConfiguration yaml = configs.regions();
        yaml.set(path + ".owner", region.owner());
        yaml.set(path + ".world", region.world());
        yaml.set(path + ".min.x", region.minX());
        yaml.set(path + ".min.y", region.minY());
        yaml.set(path + ".min.z", region.minZ());
        yaml.set(path + ".max.x", region.maxX());
        yaml.set(path + ".max.y", region.maxY());
        yaml.set(path + ".max.z", region.maxZ());
    }

    private void removeSoundConfig(String regionName) {
        YamlConfiguration yaml = configs.situational("regions.yml");
        removeProviderRegion(yaml, "KaltraSounds", regionName);
        removeProviderRegion(yaml, "PlayMoreSounds", regionName);
    }

    private void renameSoundConfig(String oldName, String newName) {
        YamlConfiguration yaml = configs.situational("regions.yml");
        for (String provider : List.of("KaltraSounds", "PlayMoreSounds")) {
            ConfigurationSection source = providerRegion(provider, oldName);
            if (source == null) continue;
            String newBase = provider + "." + newName;
            for (String path : source.getKeys(true)) {
                if (!source.isConfigurationSection(path)) yaml.set(newBase + "." + path, source.get(path));
            }
            removeProviderRegion(yaml, provider, oldName);
        }
    }

    private void removeProviderRegion(YamlConfiguration yaml, String provider, String regionName) {
        ConfigurationSection providerSection = sectionIgnoreCase(yaml, provider);
        if (providerSection == null) return;
        for (String key : providerSection.getKeys(false)) {
            if (key.equalsIgnoreCase(regionName)) {
                yaml.set(provider + "." + key, null);
                return;
            }
        }
    }

    private void deactivateNativeRegion(String name) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<String> current = active.get(player.getUniqueId());
            if (current == null) continue;
            for (String token : new ArrayList<>(current)) {
                int colon = token.indexOf(':');
                if (colon >= 0 && token.substring(colon + 1).equalsIgnoreCase(name)
                        && (token.regionMatches(true, 0, "KaltraSounds:", 0, 13) || token.regionMatches(true, 0, "PlayMoreSounds:", 0, 15))) {
                    cancelLoop(player.getUniqueId(), token, player);
                    cancelPendingStops(player.getUniqueId(), token);
                    current.remove(token);
                }
            }
        }
    }

    private void restartRegionLoops(String name) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<String> current = active.get(player.getUniqueId());
            if (current == null) continue;
            for (String token : current) {
                int colon = token.indexOf(':');
                if (colon >= 0 && token.substring(colon + 1).equalsIgnoreCase(name)) startConfiguredLoop(player, token);
            }
        }
    }

    private boolean isOwnerOrAdmin(Player player, NativeRegion region) {
        return region.owner().equals(player.getUniqueId().toString()) || player.hasPermission("playmoresounds.admin.regions") || player.hasPermission("playmoresounds.admin");
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) return "&cProvide a region name.";
        int maxChars = configs.config().getInt("regions.max-name-characters", 20);
        if (name.length() > maxChars) return "&cRegion names may contain at most " + maxChars + " characters.";
        if (!validName(name)) return "&cRegion names may contain only letters, numbers, underscores and hyphens.";
        return null;
    }

    private static boolean validName(String name) { return name != null && VALID_NAME.matcher(name).matches(); }

    private void logTransition(Player player, String verb, String token) {
        if (configs.regionLogging()) plugin.getLogger().info("Region transition: " + player.getName() + " " + verb + " " + token);
    }

    private Player onlinePlayer(UUID id) {
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getUniqueId().equals(id)) return player;
        return null;
    }

    private static ConfigurationSection sectionIgnoreCase(ConfigurationSection parent, String name) {
        if (parent == null) return null;
        for (String key : parent.getKeys(false)) if (key.equalsIgnoreCase(name)) return parent.getConfigurationSection(key);
        return null;
    }

    private Set<String> worldGuardRegions(Location location) {
        try {
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object weLocation = adapter.getMethod("adapt", Location.class).invoke(null, location);
            Class<?> worldGuard = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instance = worldGuard.getMethod("getInstance").invoke(null);
            Object platform = instance.getClass().getMethod("getPlatform").invoke(instance);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = container.getClass().getMethod("createQuery").invoke(container);
            Method applicableMethod = null;
            for (Method method : query.getClass().getMethods()) {
                if (method.getName().equals("getApplicableRegions") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(weLocation.getClass())) {
                    applicableMethod = method;
                    break;
                }
            }
            if (applicableMethod == null) throw new NoSuchMethodException("RegionQuery#getApplicableRegions(Location)");
            Object applicable = applicableMethod.invoke(query, weLocation);
            Object regions = applicable.getClass().getMethod("getRegions").invoke(applicable);
            Set<String> out = new LinkedHashSet<>();
            if (regions instanceof Iterable<?> iterable) for (Object region : iterable) out.add(String.valueOf(region.getClass().getMethod("getId").invoke(region)));
            return out;
        } catch (Throwable error) {
            warnIntegrationOnce("WorldGuard", error);
            return Collections.emptySet();
        }
    }

    private Set<String> redProtectRegions(Location location) {
        try {
            Class<?> redProtect = Class.forName("br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect");
            Object instance = redProtect.getMethod("get").invoke(null);
            Object api = instance.getClass().getMethod("getAPI").invoke(instance);
            Object region = api.getClass().getMethod("getRegion", Location.class).invoke(api, location);
            if (region == null) return Collections.emptySet();
            String name = String.valueOf(region.getClass().getMethod("getName").invoke(region));
            return Set.of(name);
        } catch (ClassNotFoundException ignored) {
            return Collections.emptySet();
        } catch (Throwable error) {
            warnIntegrationOnce("RedProtect", error);
            return Collections.emptySet();
        }
    }

    private void warnIntegrationOnce(String integration, Throwable error) {
        if (warnedIntegrationFailures.add(integration)) {
            plugin.getLogger().warning(integration + " region lookup failed; further identical warnings are suppressed for this session: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }
}
