package net.kaltra.sounds;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.logging.Level;

final class ConfigHub {
    private static final List<String> SOUND_FILES = List.of(
            "biomes.yml", "chat sounds.yml", "commands.yml", "death types.yml", "game modes.yml",
            "hit sounds.yml", "items clicked.yml", "items held.yml", "items swung.yml", "regions.yml",
            "world time triggers.yml"
    );

    private final JavaPlugin plugin;
    private final File folder;
    private YamlConfiguration config;
    private YamlConfiguration messages;
    private YamlConfiguration eventSounds;
    private YamlConfiguration regionsData;
    private YamlConfiguration discs;
    private YamlConfiguration replacements;
    private YamlConfiguration integrations;
    private YamlConfiguration playerData;
    private final Map<String, YamlConfiguration> situational = new LinkedHashMap<>();

    ConfigHub(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder();
    }

    void initialize() throws IOException {
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Could not create " + folder);
        saveDefault("config.yml");
        saveDefault("messages.yml");
        saveDefault("sounds.yml");
        saveDefault("regions-data.yml");
        saveDefault("discs.yml");
        saveDefault("nature-replacements.yml");
        saveDefault("integrations.yml");
        saveDefault("player-data.yml");
        File soundsFolder = new File(folder, "Sounds");
        if (!soundsFolder.exists() && !soundsFolder.mkdirs()) throw new IOException("Could not create Sounds folder");
        for (String name : SOUND_FILES) saveDefault("Sounds/" + name);
        File nbsFolder = new File(folder, "nbs");
        if (!nbsFolder.exists() && !nbsFolder.mkdirs()) throw new IOException("Could not create nbs folder");
        saveDefault("nbs/README.txt");
        saveDefault("CONFIG-GUIDE.txt");
        reload();
        if (config.getBoolean("migration.auto-import-playmoresounds", true)) autoImportLegacy();
        reload();
        ensureIntegrationRuleTemplates();
    }

    private void saveDefault(String path) {
        File target = new File(folder, path);
        if (!target.exists()) plugin.saveResource(path, false);
    }

    void reload() {
        config = load("config.yml");
        messages = load("messages.yml");
        reloadSounds();
        regionsData = load("regions-data.yml");
        replacements = load("nature-replacements.yml");
        integrations = load("integrations.yml");
        playerData = load("player-data.yml");
    }

    void reloadSounds() {
        eventSounds = load("sounds.yml");
        discs = load("discs.yml");
        situational.clear();
        for (String name : SOUND_FILES) situational.put(name.toLowerCase(Locale.ROOT), load("Sounds/" + name));
    }

    void reloadRegions() {
        regionsData = load("regions-data.yml");
        situational.put("regions.yml", load("Sounds/regions.yml"));
    }

    void reloadIntegrations() {
        integrations = load("integrations.yml");
        replacements = load("nature-replacements.yml");
    }

    void reloadMessages() { messages = load("messages.yml"); }

    private YamlConfiguration load(String relative) {
        return YamlConfiguration.loadConfiguration(new File(folder, relative));
    }

    boolean autoImportLegacy() throws IOException {
        File legacy = new File(folder.getParentFile(), "PlayMoreSounds");
        if (!legacy.isDirectory()) return false;
        File marker = new File(folder, ".legacy-imported");
        if (marker.exists()) return false;

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        File backup = new File(folder, "migration-backups/PlayMoreSounds-" + stamp);
        if (config.getBoolean("migration.keep-legacy-backup", true)) copyTree(legacy.toPath(), backup.toPath());

        File oldSounds = new File(legacy, "sounds.yml");
        if (oldSounds.isFile()) Files.copy(oldSounds.toPath(), new File(folder, "sounds.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
        File oldSituational = new File(legacy, "Sounds");
        if (oldSituational.isDirectory()) {
            File target = new File(folder, "Sounds");
            for (String name : SOUND_FILES) {
                File src = new File(oldSituational, name);
                if (src.isFile()) Files.copy(src.toPath(), new File(target, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String legacyNbsName : List.of("Note Block Songs", "nbs", "Songs")) {
            File oldNbs = new File(legacy, legacyNbsName);
            if (!oldNbs.isDirectory()) continue;
            File targetNbs = nbsFolder();
            File[] songs = oldNbs.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".nbs"));
            if (songs != null) for (File song : songs) Files.copy(song.toPath(), new File(targetNbs, song.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(marker.toPath(), "Imported from " + legacy.getAbsolutePath() + " at " + LocalDateTime.now());
        plugin.getLogger().info("Imported legacy PlayMoreSounds sound configuration. Resource-pack settings were intentionally ignored.");
        return true;
    }


    private void ensureIntegrationRuleTemplates() {
        Map<String, String> templates = Map.of(
                "AFK On", "block.note_block.bass",
                "AFK Off", "block.note_block.pling",
                "God Mode On", "item.totem.use",
                "God Mode Off", "block.fire.extinguish",
                "Vanish On", "entity.enderman.teleport",
                "Vanish Off", "entity.enderman.teleport",
                "Auth Register", "entity.player.levelup"
        );
        boolean changed = false;
        for (Map.Entry<String, String> entry : templates.entrySet()) {
            String path = entry.getKey();
            if (eventSounds.isConfigurationSection(path)) continue;
            eventSounds.set(path + ".Enabled", false);
            eventSounds.set(path + ".Sounds.1.Sound", entry.getValue());
            eventSounds.set(path + ".Sounds.1.Volume", 1.0);
            eventSounds.set(path + ".Sounds.1.Pitch", 1.0);
            changed = true;
        }
        if (changed && !saveEventSounds()) plugin.getLogger().warning("Could not save disabled integration sound templates to sounds.yml.");
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    List<String> validate() {
        List<String> problems = new ArrayList<>();
        validateCoreConfig(problems);
        validateRuleFile("sounds.yml", eventSounds, problems);
        for (Map.Entry<String, YamlConfiguration> entry : situational.entrySet()) {
            validateRuleFile("Sounds/" + entry.getKey(), entry.getValue(), problems);
        }
        validateDiscs(problems);
        validateReplacements(problems);
        ConfigurationSection regions = regionsData.getConfigurationSection("regions");
        if (regions != null) {
            Set<String> names = new HashSet<>();
            for (String name : regions.getKeys(false)) {
                ConfigurationSection sec = regions.getConfigurationSection(name);
                if (sec == null) continue;
                if (!name.matches("[A-Za-z0-9_-]+")) problems.add("regions-data.yml: region name '" + name + "' contains unsupported characters");
                if (!names.add(name.toLowerCase(Locale.ROOT))) problems.add("regions-data.yml: duplicate region name ignoring case: " + name);
                if (sec.getString("world", "").isBlank()) problems.add("regions-data.yml: regions." + name + ".world is empty");
                ConfigurationSection min = sec.getConfigurationSection("min");
                ConfigurationSection max = sec.getConfigurationSection("max");
                if (min == null || max == null) {
                    problems.add("regions-data.yml: region " + name + " is missing min/max corners");
                    continue;
                }
                if (min.getInt("x") > max.getInt("x") || min.getInt("y") > max.getInt("y") || min.getInt("z") > max.getInt("z")) {
                    problems.add("regions-data.yml: region " + name + " has inverted min/max coordinates (it will be normalized on load)");
                }
            }
        }
        return problems;
    }

    private void validateCoreConfig(List<String> problems) {
        if (config.getInt("performance.location-check-interval-ticks", 10) < 1) problems.add("config.yml: performance.location-check-interval-ticks must be at least 1");
        if (config.getInt("performance.world-time-check-interval-ticks", 20) < 1) problems.add("config.yml: performance.world-time-check-interval-ticks must be at least 1");
        if (config.getInt("performance.max-scheduled-sounds-per-player", 128) < 1) problems.add("config.yml: performance.max-scheduled-sounds-per-player must be at least 1");
        int rows = config.getInt("list.gui-rows-per-page", 5);
        if (rows < 1 || rows > 6) problems.add("config.yml: list.gui-rows-per-page must be between 1 and 6");
        if (config.getInt("list.chat-max-per-page", 12) < 1) problems.add("config.yml: list.chat-max-per-page must be at least 1");
        if (config.getLong("regions.max-area", 15625L) < 1L) problems.add("config.yml: regions.max-area must be at least 1");
        if (config.getInt("regions.max-name-characters", 20) < 1) problems.add("config.yml: regions.max-name-characters must be at least 1");
        if (config.getInt("regions.max-regions-per-player", 5) < 1) problems.add("config.yml: regions.max-regions-per-player must be at least 1");
        String wandName = config.getString("regions.wand.material", "FEATHER");
        Material wand = Material.matchMaterial(wandName);
        if (wand == null || !wand.isItem()) problems.add("config.yml: regions.wand.material is not a usable Paper Material: " + wandName);
    }

    private void validateRuleFile(String name, YamlConfiguration yaml, List<String> problems) {
        ConfigurationSection regex = sectionIgnoreCase(yaml, "Regex");
        if (regex != null) {
            for (String expression : regex.getKeys(false)) {
                try {
                    Pattern.compile(expression);
                } catch (PatternSyntaxException ex) {
                    problems.add(name + ": Regex." + expression + " is invalid: " + ex.getDescription());
                }
            }
        }
        walkRules(name, "", yaml, problems);
    }

    private void walkRules(String file, String path, ConfigurationSection section, List<String> problems) {
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase("Version")) continue;
            String next = path.isEmpty() ? key : path + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) continue;
            if (key.equalsIgnoreCase("Loop")) validateLoopSettings(file, next, child, problems);
            ConfigurationSection sounds = child.getConfigurationSection("Sounds");
            if (sounds != null) {
                for (String id : sounds.getKeys(false)) {
                    ConfigurationSection sound = sounds.getConfigurationSection(id);
                    if (sound == null) continue;
                    String soundKey = sound.getString("Sound", "").trim();
                    if (soundKey.isEmpty()) problems.add(file + ": " + next + ".Sounds." + id + ".Sound is empty");
                    double volume = sound.getDouble("Volume", 1.0);
                    double pitch = sound.getDouble("Pitch", 1.0);
                    long delay = sound.getLong("Delay", 0);
                    ConfigurationSection options = sound.getConfigurationSection("Options");
                    double radius = options == null ? 0.0 : options.getDouble("Radius", 0.0);
                    String soundPath = file + ": " + next + ".Sounds." + id;
                    if (SoundEngine.resolveSoundKey(soundKey) == null) problems.add(soundPath + ".Sound is not a resolvable legacy, dotted, or namespaced key");
                    if (!Double.isFinite(volume) || volume < 0) problems.add(soundPath + ".Volume must be finite and non-negative");
                    if (!Double.isFinite(pitch) || pitch <= 0) problems.add(soundPath + ".Pitch must be finite and greater than zero");
                    if (delay < 0) problems.add(soundPath + ".Delay cannot be negative");
                    if (!validRadius(radius)) problems.add(soundPath + ".Options.Radius must be finite and either -2, -1, or non-negative");
                }
            }
            walkRules(file, next, child, problems);
        }
    }

    private void validateLoopSettings(String file, String path, ConfigurationSection loop, List<String> problems) {
        String base = file + ": " + path;
        long delay = loop.getLong("Delay", 0L);
        long period = loop.getLong("Period", 100L);
        long randomness = loop.getLong("Period Randomness", 0L);
        int maximumPlays = loop.getInt("Maximum Plays", 0);
        if (delay < 0L) problems.add(base + ".Delay cannot be negative");
        if (period < 1L) problems.add(base + ".Period must be at least 1 tick");
        if (randomness < 0L) problems.add(base + ".Period Randomness cannot be negative");
        if (maximumPlays < 0) problems.add(base + ".Maximum Plays cannot be negative (0 means unlimited)");

        ConfigurationSection fadeIn = sectionIgnoreCase(loop, "Fade In");
        if (fadeIn != null && fadeIn.getBoolean("Enabled", false)) {
            int plays = fadeIn.getInt("Plays", 3);
            double start = fadeIn.getDouble("Start Volume Multiplier", 0.2);
            if (plays < 2 || plays > 1_000) problems.add(base + ".Fade In.Plays must be between 2 and 1000");
            if (!validMultiplier(start)) problems.add(base + ".Fade In.Start Volume Multiplier must be finite and between 0 and 1");
            validateFadeCurve(base + ".Fade In.Curve", fadeIn.getString("Curve", "SMOOTHSTEP"), problems);
            if (maximumPlays > 0 && plays > maximumPlays) {
                problems.add(base + ": Fade In.Plays exceeds Maximum Plays, so full volume will never be reached");
            }
        }

        ConfigurationSection stop = sectionIgnoreCase(loop, "Stop On Exit");
        if (stop == null) return;
        long stopDelay = stop.getLong("Delay", 0L);
        if (stopDelay < 0L) problems.add(base + ".Stop On Exit.Delay cannot be negative");
        ConfigurationSection fadeOut = sectionIgnoreCase(stop, "Fade Out");
        if (fadeOut != null && fadeOut.getBoolean("Enabled", false)) {
            if (!stop.getBoolean("Enabled", false)) problems.add(base + ".Stop On Exit.Fade Out is enabled while Stop On Exit is disabled");
            int plays = fadeOut.getInt("Plays", 3);
            long fadePeriod = fadeOut.getLong("Period", period);
            double end = fadeOut.getDouble("End Volume Multiplier", 0.0);
            if (plays < 1 || plays > 1_000) problems.add(base + ".Stop On Exit.Fade Out.Plays must be between 1 and 1000");
            if (fadePeriod < 1L) problems.add(base + ".Stop On Exit.Fade Out.Period must be at least 1 tick");
            if (!validMultiplier(end)) problems.add(base + ".Stop On Exit.Fade Out.End Volume Multiplier must be finite and between 0 and 1");
            validateFadeCurve(base + ".Stop On Exit.Fade Out.Curve", fadeOut.getString("Curve", "SMOOTHSTEP"), problems);
        }
    }

    private static void validateFadeCurve(String path, String value, List<String> problems) {
        String normalized = value == null ? "" : value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        if (!Set.of("LINEAR", "SMOOTHSTEP", "EXPONENTIAL").contains(normalized)) {
            problems.add(path + " must be LINEAR, SMOOTHSTEP, or EXPONENTIAL");
        }
    }

    private static boolean validMultiplier(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private void validateDiscs(List<String> problems) {
        ConfigurationSection root = discs.getConfigurationSection("discs");
        if (root == null) return;
        Set<String> ids = new HashSet<>();
        for (String id : root.getKeys(false)) {
            if (id.equalsIgnoreCase("Version")) continue;
            ConfigurationSection disc = root.getConfigurationSection(id);
            if (disc == null || !disc.getBoolean("enabled", false)) continue;
            String base = "discs.yml: discs." + id;
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) problems.add(base + " id must contain 1-64 letters, numbers, underscores, or hyphens");
            if (!ids.add(id.toLowerCase(Locale.ROOT))) problems.add("discs.yml: duplicate enabled disc id ignoring case: " + id);

            String materialName = disc.getString("material", "").trim();
            Material material = Material.matchMaterial(materialName);
            if (material == null || !material.isItem()) problems.add(base + ".material is not a usable Paper Material: " + materialName);

            String nbs = disc.getString("nbs", "").trim();
            String sound = disc.getString("sound", "").trim();
            ConfigurationSection sounds = sectionIgnoreCase(disc, "sounds");
            boolean hasMultiple = sounds != null && !sounds.getKeys(false).isEmpty();
            if (nbs.isEmpty() && sound.isEmpty() && !hasMultiple) problems.add(base + " has no sound, sounds section, or NBS file");
            if (!nbs.isEmpty()) {
                File song = resolveNbsFile(nbs);
                if (song == null) problems.add(base + ".nbs is missing or escapes the nbs folder: " + nbs);
                else {
                    try {
                        NbsModule.readValidated(song);
                    } catch (IOException ex) {
                        problems.add(base + ".nbs is invalid: " + ex.getMessage());
                    }
                }
                if (!sound.isEmpty() || hasMultiple) problems.add(base + ": nbs takes precedence; scalar/multi sounds will be ignored");
            }

            validateDiscSound(base, disc, disc, problems, !sound.isEmpty());
            if (sounds != null) {
                for (String key : sounds.getKeys(false)) {
                    ConfigurationSection child = sounds.getConfigurationSection(key);
                    if (child == null) {
                        problems.add(base + ".sounds." + key + " must be a section");
                        continue;
                    }
                    validateDiscSound(base + ".sounds." + key, child, disc, problems, true);
                }
            }
        }
    }

    private void validateDiscSound(String base, ConfigurationSection source, ConfigurationSection defaults,
                                   List<String> problems, boolean required) {
        String sound = stringIgnoreCase(source, "sound", stringIgnoreCase(defaults, "sound", "")).trim();
        if (required && SoundEngine.resolveSoundKey(sound) == null) problems.add(base + ".sound is not resolvable");
        double volume = doubleIgnoreCase(source, "volume", doubleIgnoreCase(defaults, "volume", 1.0));
        double pitch = doubleIgnoreCase(source, "pitch", doubleIgnoreCase(defaults, "pitch", 1.0));
        double radius = doubleIgnoreCase(source, "radius", doubleIgnoreCase(defaults, "radius", 24.0));
        long delay = longIgnoreCase(source, "delay", longIgnoreCase(defaults, "delay", 0L));
        validateFinite(base + ".volume", volume, true, problems);
        validateFinite(base + ".pitch", pitch, false, problems);
        if (!validRadius(radius)) problems.add(base + ".radius must be finite and either -2, -1, or non-negative");
        if (delay < 0) problems.add(base + ".delay cannot be negative");
    }

    private File resolveNbsFile(String name) {
        String requested = name.toLowerCase(Locale.ROOT).endsWith(".nbs") ? name : name + ".nbs";
        try {
            File root = nbsFolder().getCanonicalFile();
            File song = new File(root, requested).getCanonicalFile();
            if (!song.getPath().startsWith(root.getPath() + File.separator)) return null;
            return song.isFile() ? song : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private void validateReplacements(List<String> problems) {
        if (!replacements.getBoolean("enabled", false)) return;
        ConfigurationSection root = replacements.getConfigurationSection("replacements");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection replacement = root.getConfigurationSection(id);
            if (replacement == null || !replacement.getBoolean("enabled", false)) continue;
            String base = "nature-replacements.yml: replacements." + id;
            String sound = replacement.getString("sound", "").trim();
            if (SoundEngine.resolveSoundKey(sound) == null) problems.add(base + ".sound is not resolvable");
            validateFinite(base + ".volume", replacement.getDouble("volume", 1.0), true, problems);
            validateFinite(base + ".pitch", replacement.getDouble("pitch", 1.0), false, problems);
        }
    }

    private static ConfigurationSection sectionIgnoreCase(ConfigurationSection source, String name) {
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getConfigurationSection(key);
        return null;
    }

    private static String stringIgnoreCase(ConfigurationSection source, String name, String fallback) {
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getString(key, fallback);
        return fallback;
    }

    private static double doubleIgnoreCase(ConfigurationSection source, String name, double fallback) {
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getDouble(key, fallback);
        return fallback;
    }

    private static long longIgnoreCase(ConfigurationSection source, String name, long fallback) {
        for (String key : source.getKeys(false)) if (key.equalsIgnoreCase(name)) return source.getLong(key, fallback);
        return fallback;
    }

    private static void validateFinite(String path, double value, boolean zeroAllowed, List<String> problems) {
        if (!Double.isFinite(value) || (zeroAllowed ? value < 0.0 : value <= 0.0)) {
            problems.add(path + (zeroAllowed ? " must be finite and non-negative" : " must be finite and greater than zero"));
        }
    }

    private static boolean validRadius(double radius) {
        return Double.isFinite(radius) && (radius >= 0.0 || radius == -1.0 || radius == -2.0);
    }

    List<String> editableFiles() {
        List<String> files = new ArrayList<>();
        files.add("sounds.yml");
        for (String name : SOUND_FILES) files.add("Sounds/" + name);
        return files;
    }

    YamlConfiguration editableFile(String requested) {
        String normalized = normalizeEditableName(requested);
        if (normalized.equals("sounds.yml")) return eventSounds;
        if (normalized.startsWith("sounds/")) return situational.get(normalized.substring("sounds/".length()));
        return null;
    }

    String editableFileName(String requested) {
        String normalized = normalizeEditableName(requested);
        if (normalized.equals("sounds.yml")) return "sounds.yml";
        if (normalized.startsWith("sounds/") && situational.containsKey(normalized.substring("sounds/".length()))) {
            return "Sounds/" + normalized.substring("sounds/".length());
        }
        return null;
    }

    boolean saveEditableFile(String requested) {
        String file = editableFileName(requested);
        YamlConfiguration yaml = editableFile(requested);
        return file != null && yaml != null && save(yaml, file);
    }

    private static String normalizeEditableName(String requested) {
        String normalized = requested == null ? "" : requested.trim().replace('\\', '/').replace('_', ' ').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sounds/") || normalized.equals("sounds.yml")) return normalized;
        if (!normalized.endsWith(".yml")) normalized += ".yml";
        return normalized.equals("sounds.yml") ? normalized : "sounds/" + normalized;
    }

    boolean saveEventSounds() { return save(eventSounds, "sounds.yml"); }
    boolean saveRegions() { return save(regionsData, "regions-data.yml"); }

    boolean saveSituational(String name) {
        YamlConfiguration yaml = situational(name);
        return yaml != null && save(yaml, "Sounds/" + name);
    }
    boolean saveDiscs() { return save(discs, "discs.yml"); }
    boolean savePlayerData() { return save(playerData, "player-data.yml"); }

    private boolean save(YamlConfiguration yaml, String path) {
        File target = new File(folder, path);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            Files.createDirectories(target.toPath().getParent());
            Files.writeString(temp.toPath(), yaml.saveToString());
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + path, e);
            try { Files.deleteIfExists(temp.toPath()); } catch (IOException ignored) { }
            return false;
        }
    }

    String message(String key) { return messages.getString(key, key); }
    String prefix() { return messages.getString("prefix", "&8[&bKaltraSounds&8] "); }

    boolean module(String name) { return config.getBoolean("modules." + name, true); }
    boolean integration(String name) { return integrations.getBoolean("integrations." + name, true); }
    int locationInterval() { return Math.max(1, config.getInt("performance.location-check-interval-ticks", 10)); }
    int worldTimeInterval() { return Math.max(1, config.getInt("performance.world-time-check-interval-ticks", 20)); }
    int listPageSize() { return Math.max(1, config.getInt("list.chat-max-per-page", 12)); }
    int guiRows() { return Math.max(1, Math.min(6, config.getInt("list.gui-rows-per-page", 5))); }
    boolean debug() { return config.getBoolean("logging.debug", false); }
    boolean commandActionLogging() { return config.getBoolean("logging.command-actions", true); }
    boolean regionLogging() { return config.getBoolean("logging.region-transitions", false); }
    boolean setDebug(boolean value) {
        Object previous = config.get("logging.debug");
        config.set("logging.debug", value);
        if (save(config, "config.yml")) return true;
        config.set("logging.debug", previous);
        return false;
    }

    YamlConfiguration config() { return config; }
    YamlConfiguration events() { return eventSounds; }
    YamlConfiguration situational(String name) { return situational.get(name.toLowerCase(Locale.ROOT)); }
    YamlConfiguration regions() { return regionsData; }
    YamlConfiguration discs() { return discs; }
    YamlConfiguration replacements() { return replacements; }
    File nbsFolder() { return new File(folder, "nbs"); }
    File folder() { return folder; }

    boolean soundsEnabled(UUID uuid) { return playerData.getBoolean("players." + uuid + ".enabled", true); }
    boolean setSoundsEnabled(UUID uuid, boolean enabled) {
        String path = "players." + uuid + ".enabled";
        Object previous = playerData.get(path);
        playerData.set(path, enabled);
        if (savePlayerData()) return true;
        playerData.set(path, previous);
        return false;
    }

    Set<String> eventNames() { return eventSounds.getKeys(false); }
    ConfigurationSection eventRule(String name) {
        for (String key : eventSounds.getKeys(false)) if (key.equalsIgnoreCase(name)) return eventSounds.getConfigurationSection(key);
        return null;
    }

    Map<String, YamlConfiguration> situationalFiles() { return Collections.unmodifiableMap(situational); }
}
