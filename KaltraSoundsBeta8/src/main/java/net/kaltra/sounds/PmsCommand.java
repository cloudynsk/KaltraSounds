package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class PmsCommand implements CommandExecutor, TabCompleter {
    private final KaltraSoundsPlugin plugin;
    private final ConfigHub configs;
    private final SoundEngine engine;
    private final RegionModule regions;
    private final DiscModule discs;
    private final NbsModule nbs;
    private final SoundListGui gui;
    private final IntegrationModule integrations;

    PmsCommand(KaltraSoundsPlugin plugin, ConfigHub configs, SoundEngine engine, RegionModule regions, DiscModule discs, NbsModule nbs, SoundListGui gui, IntegrationModule integrations) {
        this.plugin = plugin;
        this.configs = configs;
        this.engine = engine;
        this.regions = regions;
        this.discs = discs;
        this.nbs = nbs;
        this.gui = gui;
        this.integrations = integrations;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return help(sender);
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help", "?" -> help(sender);
            case "reload" -> reload(sender, args);
            case "edit" -> edit(sender, args);
            case "validate" -> validate(sender);
            case "info" -> info(sender);
            case "list" -> list(sender, args);
            case "play" -> play(sender, args);
            case "stop", "stopsound" -> stop(sender, args);
            case "toggle" -> toggle(sender, args);
            case "check" -> check(sender, args);
            case "test" -> test(sender, args);
            case "debug" -> debug(sender, args);
            case "migrate" -> migrate(sender);
            case "region", "regions" -> region(sender, args);
            case "disc", "discs" -> disc(sender, args);
            case "nbs", "song" -> nbs(sender, args);
            case "addons" -> addons(sender);
            default -> help(sender);
        };
    }

    private boolean help(CommandSender sender) {
        if (!permission(sender, "playmoresounds.help")) return true;
        send(sender, "&bKaltraSounds &7v" + plugin.getDescription().getVersion());
        send(sender, "&f/pms reload [scope] &7- Reload configuration and modules.");
        send(sender, "&f/pms edit <file> <path> <value> &7- Edit an existing sound setting.");
        send(sender, "&f/pms validate &7- Validate every sound configuration path.");
        send(sender, "&f/pms info &7- Show modules and integration status.");
        send(sender, "&f/pms list [page|gui|search] &7- Browse available sounds.");
        send(sender, "&f/pms play <sound> [player] [volume] [pitch]");
        send(sender, "&f/pms stop [player] [sound|all]");
        send(sender, "&f/pms toggle [player] &7- Toggle event sounds.");
        send(sender, "&f/pms test event <event> &7- Preview an event rule.");
        send(sender, "&f/pms region ... &7- Native and external region tools.");
        send(sender, "&f/pms disc ... &7- Custom disc tools.");
        send(sender, "&f/pms nbs ... &7- NBS song playback.");
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.reload")) return true;
        String scope = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "all";
        long started = System.nanoTime();
        if (configs.commandActionLogging()) plugin.getLogger().info("Reload requested by " + actorName(sender) + " (scope=" + scope + ").");
        try {
            plugin.reloadScope(scope);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            List<String> problems = configs.validate();
            send(sender, Text.replace(configs.message("reloaded"), Map.of("scope", scope)));
            if (configs.commandActionLogging()) {
                plugin.getLogger().info("Reload complete in " + elapsedMs + " ms: "
                        + regions.allNative().size() + " native region(s), "
                        + problems.size() + " validation problem(s).");
            }
        } catch (Exception e) {
            send(sender, "&cReload failed: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Reload failed for " + actorName(sender) + " (scope=" + scope + ")", e);
        }
        return true;
    }

    private boolean edit(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.edit")) return true;
        if (args.length < 4) {
            send(sender, "&cUsage: /pms edit <file> <existing.path> <new value>");
            return true;
        }
        String requestedFile = args[1];
        YamlConfiguration yaml = configs.editableFile(requestedFile);
        String fileName = configs.editableFileName(requestedFile);
        if (yaml == null || fileName == null) {
            send(sender, "&cEditable files: &f" + String.join(", ", configs.editableFiles()));
            return true;
        }
        String resolvedPath = resolveExistingPath(yaml, args[2]);
        if (resolvedPath == null) {
            send(sender, "&cThat path does not already exist in &f" + fileName + "&c.");
            return true;
        }
        Object oldValue = yaml.get(resolvedPath);
        String rawValue = join(args, 3);
        Object parsed;
        try {
            parsed = parseEditedValue(oldValue, rawValue);
            validateEditedValue(resolvedPath, parsed);
        } catch (IllegalArgumentException ex) {
            send(sender, "&c" + ex.getMessage());
            return true;
        }
        yaml.set(resolvedPath, parsed);
        if (!configs.saveEditableFile(requestedFile)) {
            yaml.set(resolvedPath, oldValue);
            send(sender, "&cThe file could not be saved; the in-memory change was rolled back.");
            return true;
        }
        try {
            plugin.reloadScope("sounds");
        } catch (Exception ex) {
            send(sender, "&cThe value was saved, but sound modules could not reload: " + ex.getMessage());
            return true;
        }
        if (configs.commandActionLogging()) {
            plugin.getLogger().info("Config edit by " + actorName(sender) + ": " + fileName + " " + resolvedPath + " = " + parsed);
        }
        send(sender, "&aUpdated &f" + fileName + " &7» &f" + resolvedPath + "&a.");
        return true;
    }

    private static String resolveExistingPath(ConfigurationSection root, String requested) {
        String[] parts = requested.split("\\.");
        ConfigurationSection section = root;
        List<String> actual = new ArrayList<>();
        for (int index = 0; index < parts.length; index++) {
            String wanted = parts[index].replace('_', ' ');
            String match = null;
            for (String key : section.getKeys(false)) {
                if (key.equalsIgnoreCase(wanted) || key.replace(' ', '_').equalsIgnoreCase(parts[index])) {
                    match = key;
                    break;
                }
            }
            if (match == null) return null;
            actual.add(match);
            if (index < parts.length - 1) {
                section = section.getConfigurationSection(match);
                if (section == null) return null;
            }
        }
        return String.join(".", actual);
    }

    private static Object parseEditedValue(Object oldValue, String raw) {
        String value = raw.trim();
        if (oldValue instanceof Boolean) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("Value must be true or false.");
            return Boolean.parseBoolean(value);
        }
        try {
            if (oldValue instanceof Integer) return Integer.parseInt(value);
            if (oldValue instanceof Long) return Long.parseLong(value);
            if (oldValue instanceof Float) return Float.parseFloat(value);
            if (oldValue instanceof Double) return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Value must be the same numeric type as the existing setting.");
        }
        if (oldValue instanceof List<?>) throw new IllegalArgumentException("List values are not editable through this command.");
        return raw;
    }

    private static void validateEditedValue(String path, Object value) {
        String leaf = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (leaf.equals("sound") && SoundEngine.resolveSoundKey(String.valueOf(value)) == null) {
            throw new IllegalArgumentException("Sound must be a valid legacy constant, dotted key, or namespaced key.");
        }
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) throw new IllegalArgumentException("Numeric values must be finite.");
            if (leaf.equals("volume") && numeric < 0.0) throw new IllegalArgumentException("Volume cannot be negative.");
            if (leaf.equals("pitch") && numeric <= 0.0) throw new IllegalArgumentException("Pitch must be greater than zero.");
            if (leaf.equals("radius") && numeric < 0.0 && numeric != -1.0 && numeric != -2.0) throw new IllegalArgumentException("Radius must be -2, -1, or non-negative.");
            if (leaf.equals("delay") && numeric < 0.0) throw new IllegalArgumentException("Delay cannot be negative.");
            if (leaf.equals("period") && numeric <= 0.0) throw new IllegalArgumentException("Period must be greater than zero.");
        }
    }

    private boolean validate(CommandSender sender) {
        if (!permission(sender, "playmoresounds.validate")) return true;
        List<String> problems = configs.validate();
        if (problems.isEmpty()) send(sender, configs.message("validation-ok"));
        else {
            send(sender, "&cFound " + problems.size() + " configuration problem(s):");
            for (String problem : problems) sender.sendMessage(Text.color("&8- &f" + problem));
        }
        return true;
    }

    private boolean info(CommandSender sender) {
        if (!permission(sender, "playmoresounds.info")) return true;
        send(sender, "&bKaltraSounds &f" + plugin.getDescription().getVersion());
        send(sender, "&7Paper target: &f26.1.1 &8| &7Java runtime: &f" + System.getProperty("java.version"));
        send(sender, "&7Event rules: &f" + configs.eventNames().stream().filter(k -> !k.equalsIgnoreCase("Version")).count());
        send(sender, "&7Native regions: &f" + regions.allNative().size());
        send(sender, "&7NBS songs: &f" + nbs.listSongs().size());
        for (Map.Entry<String, String> entry : integrations.status().entrySet()) send(sender, "&7" + entry.getKey() + ": &f" + entry.getValue());
        return true;
    }

    private boolean list(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.list")) return true;
        if (args.length > 1 && args[1].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) { send(sender, configs.message("player-only")); return true; }
            if (!permission(sender, "playmoresounds.list.gui")) return true;
            int page = args.length > 2 ? parseInt(args[2], 1) : 1;
            gui.open(player, page, "");
            return true;
        }
        int page = 1;
        String search = "";
        if (args.length > 1) {
            try { page = Integer.parseInt(args[1]); }
            catch (NumberFormatException ignored) { search = join(args, 1); }
        }
        List<String> sounds = gui.sounds(search);
        int size = configs.listPageSize();
        int pages = Math.max(1, (sounds.size() + size - 1) / size);
        page = Math.max(1, Math.min(page, pages));
        send(sender, "&bSounds &7page &f" + page + "&7/&f" + pages + (search.isBlank() ? "" : " &7search: &f" + search));
        for (int i = (page - 1) * size; i < Math.min(sounds.size(), page * size); i++) sender.sendMessage(Text.color("&8- &f" + sounds.get(i)));
        return true;
    }

    private boolean play(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.play")) return true;
        if (args.length < 2) { send(sender, "&cUsage: /pms play <sound> [player] [volume] [pitch]"); return true; }
        String sound = args[1];
        Player target = sender instanceof Player p ? p : null;
        if (args.length > 2) {
            if (!permission(sender, "playmoresounds.play.others")) return true;
            target = Bukkit.getPlayerExact(args[2]);
        }
        if (target == null) { send(sender, Text.replace(configs.message("unknown-player"), Map.of("player", args.length > 2 ? args[2] : "console"))); return true; }
        Float volume = args.length > 3 ? finiteFloat(sender, args[3], "volume", true) : 1.0f;
        Float pitch = args.length > 4 ? finiteFloat(sender, args[4], "pitch", false) : 1.0f;
        if (volume == null || pitch == null) return true;
        boolean played = engine.playDirect(target, sound, volume, pitch);
        if (played) send(sender, "&aPlayed &f" + sound + " &ato &f" + target.getName() + "&a.");
        else send(sender, "&cCould not resolve or dispatch sound &f" + sound + "&c.");
        return true;
    }

    private boolean stop(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.stopsound")) return true;
        Player target = sender instanceof Player p ? p : null;
        int soundIndex = 1;
        if (args.length > 1 && Bukkit.getPlayerExact(args[1]) != null) {
            if (!permission(sender, "playmoresounds.stopsound.others")) return true;
            target = Bukkit.getPlayerExact(args[1]);
            soundIndex = 2;
        }
        if (target == null) { send(sender, configs.message("player-only")); return true; }
        String sound = args.length > soundIndex ? args[soundIndex] : "all";
        if (sound.equalsIgnoreCase("all")) {
            engine.stopAll(target);
            send(sender, "&aStopped all sounds for &f" + target.getName() + "&a.");
        } else if (engine.stop(target, sound)) {
            send(sender, "&aStopped &f" + sound + " &afor &f" + target.getName() + "&a.");
        } else {
            send(sender, "&cCould not resolve or stop sound &f" + sound + "&c.");
        }
        return true;
    }

    private boolean toggle(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.toggle")) return true;
        Player target = sender instanceof Player p ? p : null;
        if (args.length > 1) {
            if (!permission(sender, "playmoresounds.toggle.others")) return true;
            target = Bukkit.getPlayerExact(args[1]);
        }
        if (target == null) { send(sender, configs.message("player-only")); return true; }
        boolean enabled = !configs.soundsEnabled(target.getUniqueId());
        if (!configs.setSoundsEnabled(target.getUniqueId(), enabled)) {
            send(sender, "&cCould not save player-data.yml; the toggle was rolled back.");
            return true;
        }
        Text.send(target, configs.prefix(), configs.message(enabled ? "toggle-on" : "toggle-off"));
        if (!sender.equals(target)) send(sender, "&aSet sounds for &f" + target.getName() + " &ato &f" + enabled + "&a.");
        if (configs.commandActionLogging()) {
            plugin.getLogger().info("Event sounds toggled " + (enabled ? "ON" : "OFF") + " for " + target.getName() + " by " + actorName(sender) + ".");
        }
        return true;
    }

    private boolean check(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.toggle.check")) return true;
        Player target = sender instanceof Player p ? p : null;
        if (args.length > 1) {
            if (!permission(sender, "playmoresounds.toggle.check.others")) return true;
            target = Bukkit.getPlayerExact(args[1]);
        }
        if (target == null) { send(sender, configs.message("player-only")); return true; }
        send(sender, "&7Sounds for &f" + target.getName() + "&7: &f" + (configs.soundsEnabled(target.getUniqueId()) ? "enabled" : "disabled"));
        return true;
    }

    private boolean test(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.test")) return true;
        if (!(sender instanceof Player player)) { send(sender, configs.message("player-only")); return true; }
        if (args.length < 3 || !args[1].equalsIgnoreCase("event")) { send(sender, "&cUsage: /pms test event <event>"); return true; }
        String event = join(args, 2);
        if (!engine.playEvent(event, player, player.getLocation())) send(sender, "&eNo enabled rule found for &f" + event + "&e.");
        else send(sender, "&aTested event &f" + event + "&a.");
        return true;
    }

    private boolean debug(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.debug")) return true;
        if (args.length < 2) { send(sender, "&7Debug is &f" + configs.debug() + "&7."); return true; }
        if (!List.of("on", "off", "true", "false").contains(args[1].toLowerCase(Locale.ROOT))) {
            send(sender, "&cUsage: /pms debug <on|off>");
            return true;
        }
        boolean enabled = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
        if (!configs.setDebug(enabled)) {
            send(sender, "&cCould not save config.yml; debug mode was not changed.");
            return true;
        }
        send(sender, "&aDebug set to &f" + enabled + "&a.");
        return true;
    }

    private boolean migrate(CommandSender sender) {
        if (!permission(sender, "playmoresounds.migrate")) return true;
        try {
            boolean imported = configs.autoImportLegacy();
            if (imported) {
                plugin.reloadEverything();
                send(sender, configs.message("legacy-migrated"));
            } else send(sender, configs.message("legacy-not-found"));
        } catch (IOException e) {
            send(sender, "&cMigration failed: " + e.getMessage());
        }
        return true;
    }

    private boolean region(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.region")) return true;
        if (args.length < 2) {
            send(sender, "&f/pms region <wand|pos1|pos2|create|remove|rename|info|list|sound>");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        Player player = sender instanceof Player p ? p : null;
        switch (action) {
            case "wand" -> {
                if (!permission(sender, "playmoresounds.region.wand")) return true;
                if (player == null) { send(sender, configs.message("player-only")); return true; }
                player.getInventory().addItem(regions.createWand());
                send(sender, "&aRegion wand added to your inventory.");
            }
            case "pos1", "pos2" -> {
                if (!permission(sender, "playmoresounds.region.wand")) return true;
                if (player == null) { send(sender, configs.message("player-only")); return true; }
                if (action.equals("pos1")) regions.setPos1(player, player.getLocation()); else regions.setPos2(player, player.getLocation());
                send(sender, configs.message(action.equals("pos1") ? "region-pos1" : "region-pos2"));
            }
            case "create" -> {
                if (player == null) { send(sender, configs.message("player-only")); return true; }
                if (args.length < 3) { send(sender, "&cUsage: /pms region create <name>"); return true; }
                send(sender, regions.create(player, args[2]));
            }
            case "remove" -> {
                if (player == null) { send(sender, configs.message("player-only")); return true; }
                if (args.length < 3) { send(sender, "&cUsage: /pms region remove <name>"); return true; }
                send(sender, regions.remove(player, args[2]));
            }
            case "rename" -> {
                if (player == null) { send(sender, configs.message("player-only")); return true; }
                if (args.length < 4) { send(sender, "&cUsage: /pms region rename <old> <new>"); return true; }
                send(sender, regions.rename(player, args[2], args[3]));
            }
            case "info" -> {
                if (!permission(sender, "playmoresounds.region.info")) return true;
                if (args.length < 3) {
                    if (player == null) { send(sender, "&cUsage: /pms region info <name>"); return true; }
                    send(sender, "&7Regions here: &f" + String.join(", ", regions.regionsAt(player)));
                    return true;
                }
                NativeRegion region = regions.find(args[2]);
                if (region == null) send(sender, "&cRegion not found.");
                else if (player != null && !regions.canViewInfo(player, region)) send(sender, configs.message("no-permission"));
                else send(sender, "&b" + region.name() + " &7world=&f" + region.world() + " &7volume=&f" + region.volume() + " &7owner=&f" + region.owner());
            }
            case "list" -> {
                if (!permission(sender, "playmoresounds.region.list")) return true;
                send(sender, "&7Native regions: &f" + String.join(", ", regions.names(player)));
            }
            case "sound" -> {
                if (!permission(sender, "playmoresounds.region.sound")) return true;
                if (args.length < 5) { send(sender, "&cUsage: /pms region sound <region> <enter|leave|loop> <sound> [volume] [pitch] [loop-period]"); return true; }
                Double volume = args.length > 5 ? finiteDouble(sender, args[5], "volume", true) : 1.0;
                Double pitch = args.length > 6 ? finiteDouble(sender, args[6], "pitch", false) : 1.0;
                if (volume == null || pitch == null) return true;
                Long loopPeriod = null;
                if (args[3].equalsIgnoreCase("loop")) {
                    if (args.length < 8) {
                        send(sender, "&cLoop sounds require a replay period: &f/pms region sound <region> loop <sound> <volume> <pitch> <1200t|60s|3m>");
                        send(sender, "&7Set it to at least the full length of the audio file to prevent overlapping playback.");
                        return true;
                    }
                    try {
                        loopPeriod = DurationTicks.parsePositive(args[7]);
                    } catch (IllegalArgumentException ex) {
                        send(sender, "&c" + ex.getMessage());
                        return true;
                    }
                } else if (args.length > 7) {
                    send(sender, "&cA replay period can only be supplied for the loop phase.");
                    return true;
                }
                send(sender, regions.setSound(player, args[2], args[3], args[4], volume, pitch, loopPeriod));
            }
            default -> send(sender, "&cUnknown region action.");
        }
        return true;
    }

    private boolean disc(CommandSender sender, String[] args) {
        if (args.length < 2) { send(sender, "&f/pms disc <list|give|create>"); return true; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (!permission(sender, "playmoresounds.disc.list")) return true;
                send(sender, "&7Discs: &f" + String.join(", ", discs.ids()));
            }
            case "give" -> {
                if (!permission(sender, "playmoresounds.disc.give")) return true;
                if (args.length < 4) { send(sender, "&cUsage: /pms disc give <player> <id>"); return true; }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null) { send(sender, Text.replace(configs.message("unknown-player"), Map.of("player", args[2]))); return true; }
                send(sender, discs.give(target, args[3]));
            }
            case "create" -> {
                if (!permission(sender, "playmoresounds.disc.create")) return true;
                if (args.length < 4) { send(sender, "&cUsage: /pms disc create <id> <sound>"); return true; }
                send(sender, discs.create(args[2], args[3]));
            }
            default -> send(sender, "&cUnknown disc action.");
        }
        return true;
    }

    private boolean nbs(CommandSender sender, String[] args) {
        if (!permission(sender, "playmoresounds.nbs")) return true;
        if (args.length < 2) { send(sender, "&f/pms nbs <list|play|stop|pause|resume>"); return true; }
        if (args[1].equalsIgnoreCase("list")) { send(sender, "&7NBS songs: &f" + String.join(", ", nbs.listSongs())); return true; }
        if (!(sender instanceof Player player)) { send(sender, configs.message("player-only")); return true; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "play" -> send(sender, args.length < 3 ? "&cUsage: /pms nbs play <file>" : nbs.play(player, join(args, 2)));
            case "stop" -> send(sender, nbs.stop(player));
            case "pause" -> send(sender, nbs.pause(player));
            case "resume" -> send(sender, nbs.resume(player));
            default -> send(sender, "&cUnknown NBS action.");
        }
        return true;
    }

    private boolean addons(CommandSender sender) {
        if (!permission(sender, "playmoresounds.info")) return true;
        send(sender, "&aFormer PlayMoreSounds addon features are built-in modules; status follows.");
        for (Map.Entry<String, String> entry : integrations.status().entrySet()) send(sender, "&8- &f" + entry.getKey() + "&7: " + entry.getValue());
        return true;
    }

    private boolean permission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission) || sender.hasPermission("playmoresounds.admin")) return true;
        send(sender, configs.message("no-permission"));
        return false;
    }

    private void send(CommandSender sender, String message) { Text.send(sender, configs.prefix(), message); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return match(args[0], List.of("help", "reload", "edit", "validate", "info", "list", "play", "stop", "toggle", "check", "test", "debug", "migrate", "region", "disc", "nbs", "addons"));
        if (args[0].equalsIgnoreCase("reload") && args.length == 2) return match(args[1], List.of("all", "sounds", "regions", "integrations", "messages"));
        if (args[0].equalsIgnoreCase("edit") && args.length == 2) return match(args[1], configs.editableFiles());
        if ((args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("stop")) && args.length == 3) return match(args[2], onlineNames());
        if ((args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("check")) && args.length == 2) return match(args[1], onlineNames());
        if (args[0].equalsIgnoreCase("test") && args.length == 2) return match(args[1], List.of("event"));
        if (args[0].equalsIgnoreCase("test") && args.length >= 3 && args[1].equalsIgnoreCase("event")) return match(join(args, 2), configs.eventNames().stream().filter(k -> !k.equalsIgnoreCase("Version")).toList());
        if (args[0].equalsIgnoreCase("debug") && args.length == 2) return match(args[1], List.of("on", "off"));
        if (args[0].equalsIgnoreCase("list") && args.length == 2) return match(args[1], List.of("gui"));
        if (args[0].equalsIgnoreCase("region")) {
            if (args.length == 2) return match(args[1], List.of("wand", "pos1", "pos2", "create", "remove", "rename", "info", "list", "sound"));
            if (args.length == 3 && List.of("remove", "rename", "info", "sound").contains(args[1].toLowerCase(Locale.ROOT))) return match(args[2], regions.names(sender instanceof Player p ? p : null));
            if (args.length == 4 && args[1].equalsIgnoreCase("sound")) return match(args[3], List.of("enter", "leave", "loop"));
            if (args.length == 8 && args[1].equalsIgnoreCase("sound") && args[3].equalsIgnoreCase("loop")) return match(args[7], List.of("1200t", "60s", "3m"));
        }
        if (args[0].equalsIgnoreCase("disc")) {
            if (args.length == 2) return match(args[1], List.of("list", "give", "create"));
            if (args.length == 3 && args[1].equalsIgnoreCase("give")) return match(args[2], onlineNames());
            if (args.length == 4 && args[1].equalsIgnoreCase("give")) return match(args[3], discs.ids());
        }
        if (args[0].equalsIgnoreCase("nbs")) {
            if (args.length == 2) return match(args[1], List.of("list", "play", "stop", "pause", "resume"));
            if (args.length == 3 && args[1].equalsIgnoreCase("play")) return match(args[2], nbs.listSongs());
        }
        return Collections.emptyList();
    }

    private static String actorName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "CONSOLE";
    }

    private static List<String> onlineNames() { return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList(); }
    private static List<String> match(String input, List<String> options) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    private static String join(String[] args, int from) { return String.join(" ", Arrays.copyOfRange(args, from, args.length)); }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; } }

    private Float finiteFloat(CommandSender sender, String value, String name, boolean zeroAllowed) {
        try {
            float parsed = Float.parseFloat(value);
            if (!Float.isFinite(parsed) || (zeroAllowed ? parsed < 0.0f : parsed <= 0.0f)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            send(sender, "&c" + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                    + (zeroAllowed ? " must be a finite non-negative number." : " must be a finite number greater than zero."));
            return null;
        }
    }

    private Double finiteDouble(CommandSender sender, String value, String name, boolean zeroAllowed) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || (zeroAllowed ? parsed < 0.0 : parsed <= 0.0)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            send(sender, "&c" + Character.toUpperCase(name.charAt(0)) + name.substring(1)
                    + (zeroAllowed ? " must be a finite non-negative number." : " must be a finite number greater than zero."));
            return null;
        }
    }
}
