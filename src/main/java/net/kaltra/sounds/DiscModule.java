package net.kaltra.sounds;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DiscModule {
    private final ConfigHub configs;
    private final SoundEngine engine;
    private final NbsModule nbs;
    private final NamespacedKey discKey;
    private final Map<String, ActiveDisc> activeJukeboxes = new HashMap<>();

    DiscModule(JavaPlugin plugin, ConfigHub configs, SoundEngine engine, NbsModule nbs) {
        this.configs = configs;
        this.engine = engine;
        this.nbs = nbs;
        this.discKey = new NamespacedKey(plugin, "custom_disc");
    }

    boolean handle(PlayerInteractEvent event) {
        if (!configs.module("custom-discs") || event.isCancelled() || event.getAction() != Action.RIGHT_CLICK_BLOCK) return false;
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Jukebox jukebox)) return false;

        ItemStack inserted = jukebox.getRecord();
        String locationKey = locationKey(jukebox.getLocation());
        if ((inserted == null || inserted.getType() == Material.AIR) && activeJukeboxes.containsKey(locationKey)) {
            stopAt(jukebox.getLocation(), null, null);
        }
        String insertedId = rawDiscId(inserted);
        if (insertedId != null) {
            event.setCancelled(true);
            return eject(event.getPlayer(), jukebox, inserted, insertedId);
        }

        ItemStack held = event.getItem();
        String heldId = rawDiscId(held);
        if (heldId == null) return false;
        Player player = event.getPlayer();
        if (!player.hasPermission("playmoresounds.disc.use") && !player.hasPermission("playmoresounds.admin")) {
            Text.send(player, configs.prefix(), configs.message("no-permission"));
            event.setCancelled(true);
            return true;
        }
        if (inserted != null && inserted.getType() != Material.AIR) return false;
        ConfigurationSection disc = section(heldId);
        if (disc == null) return false;

        event.setCancelled(true);
        ItemStack stored = held.clone();
        stored.setAmount(1);
        jukebox.setRecord(stored);
        if (!jukebox.update(true)) {
            jukebox.setRecord(new ItemStack(Material.AIR));
            jukebox.update(true);
            Text.send(player, configs.prefix(), "&cThe jukebox rejected the custom disc.");
            return true;
        }

        stopVanillaJukebox(jukebox);
        Location origin = jukebox.getLocation();
        PlaybackResult result = playDisc(player, heldId, disc, origin);
        if (!result.success()) {
            jukebox.setRecord(new ItemStack(Material.AIR));
            jukebox.update(true);
            Text.send(player, configs.prefix(), result.message());
            return true;
        }

        if (player.getGameMode() != GameMode.CREATIVE) held.setAmount(Math.max(0, held.getAmount() - 1));
        activeJukeboxes.put(locationKey(origin), new ActiveDisc(heldId, result.soundKeys(), result.radius(), player.getUniqueId(), origin.clone()));
        if (!result.message().isBlank()) Text.send(player, configs.prefix(), result.message());
        return true;
    }

    void handleBreak(Block block) {
        if (block == null || !(block.getState() instanceof Jukebox jukebox)) return;
        ItemStack record = jukebox.getRecord();
        String id = rawDiscId(record);
        if (id != null) stopAt(jukebox.getLocation(), id, section(id));
    }

    void stopAll() {
        for (ActiveDisc active : new ArrayList<>(activeJukeboxes.values())) {
            for (String key : active.soundKeys()) engine.stopAround(active.origin(), active.radius(), key);
            Player owner = org.bukkit.Bukkit.getPlayer(active.owner());
            if (owner != null) nbs.stop(owner);
        }
        activeJukeboxes.clear();
    }

    ItemStack createItem(String id) {
        ConfigurationSection disc = section(id);
        if (disc == null) return null;
        Material material = Material.matchMaterial(disc.getString("material", "MUSIC_DISC_13"));
        if (material == null || material == Material.AIR || !material.isItem()) material = Material.MUSIC_DISC_13;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(disc.getString("name", "&b" + id)));
            List<String> lore = new ArrayList<>();
            for (String line : disc.getStringList("lore")) lore.add(Text.color(line));
            lore.add(Text.color("&8KaltraSounds Disc: " + id));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(discKey, PersistentDataType.STRING, id.toLowerCase(Locale.ROOT));
            if (disc.getBoolean("glowing", false)) applyGlint(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    String give(Player target, String id) {
        ItemStack item = createItem(id);
        if (item == null) return "&cUnknown or disabled disc: &f" + id;
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        return "&aGave &f" + id + " &ato &f" + target.getName() + "&a.";
    }

    String create(String id, String sound) {
        if (sound == null || SoundEngine.resolveSoundKey(sound) == null) return "&cProvide a valid legacy, dotted, or namespaced sound key.";
        String clean = id == null ? "" : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (clean.isBlank() || clean.length() > 64) return "&cDisc ids must contain 1-64 letters, numbers, underscores, or hyphens.";
        String path = "discs." + clean;
        if (configs.discs().isConfigurationSection(path)) return "&cThat disc already exists.";
        configs.discs().set(path + ".enabled", true);
        configs.discs().set(path + ".material", "MUSIC_DISC_13");
        configs.discs().set(path + ".name", "&b" + id);
        configs.discs().set(path + ".lore", List.of("&7Custom KaltraSounds disc."));
        configs.discs().set(path + ".glowing", false);
        configs.discs().set(path + ".sound", sound);
        configs.discs().set(path + ".volume", 1.0);
        configs.discs().set(path + ".pitch", 1.0);
        configs.discs().set(path + ".radius", 24.0);
        if (!configs.saveDiscs()) {
            configs.discs().set(path, null);
            return "&cCould not save discs.yml; the in-memory disc was rolled back.";
        }
        return "&aCreated custom disc &f" + clean + "&a.";
    }

    List<String> ids() {
        ConfigurationSection root = configs.discs().getConfigurationSection("discs");
        if (root == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            if (key.equalsIgnoreCase("Version")) continue;
            ConfigurationSection disc = root.getConfigurationSection(key);
            if (disc != null && disc.getBoolean("enabled", false)) out.add(key);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private PlaybackResult playDisc(Player player, String id, ConfigurationSection disc, Location origin) {
        String nbsFile = disc.getString("nbs", "").trim();
        if (!nbsFile.isEmpty()) {
            String message = nbs.play(player, nbsFile);
            return new PlaybackResult(message.startsWith("&a"), message, List.of(), 0.0);
        }
        List<SoundSpec> specs = specs(disc);
        if (specs.isEmpty()) return new PlaybackResult(false, "&cDisc &f" + id + " &chas no playable sounds.", List.of(), 0.0);
        List<String> keys = new ArrayList<>();
        double radius = 0.0;
        for (SoundSpec spec : specs) {
            if (!engine.play(spec, player, origin)) continue;
            keys.add(spec.key());
            radius = mergeRadius(radius, spec.radius());
        }
        if (keys.isEmpty()) return new PlaybackResult(false, "&cDisc &f" + id + " &ccould not dispatch any configured sound.", List.of(), 0.0);
        return new PlaybackResult(true, "", keys, radius);
    }

    private List<SoundSpec> specs(ConfigurationSection disc) {
        List<SoundSpec> out = new ArrayList<>();
        if (disc == null) return out;
        ConfigurationSection multiple = sectionIgnoreCase(disc, "sounds");
        if (multiple != null) {
            for (String key : multiple.getKeys(false)) {
                ConfigurationSection child = multiple.getConfigurationSection(key);
                if (child == null) continue;
                SoundSpec spec = spec(child, disc);
                if (spec != null) out.add(spec);
            }
        }
        if (out.isEmpty()) {
            SoundSpec spec = spec(disc, disc);
            if (spec != null) out.add(spec);
        }
        return out;
    }

    private SoundSpec spec(ConfigurationSection source, ConfigurationSection defaults) {
        String sound = stringIgnoreCase(source, "sound", stringIgnoreCase(defaults, "sound", "")).trim();
        if (SoundEngine.resolveSoundKey(sound) == null) return null;
        double volume = doubleIgnoreCase(source, "volume", doubleIgnoreCase(defaults, "volume", 1.0));
        double pitch = doubleIgnoreCase(source, "pitch", doubleIgnoreCase(defaults, "pitch", 1.0));
        double radius = doubleIgnoreCase(source, "radius", doubleIgnoreCase(defaults, "radius", 24.0));
        long delay = Math.max(0L, longIgnoreCase(source, "delay", longIgnoreCase(defaults, "delay", 0L)));
        if (!Double.isFinite(volume) || volume < 0.0 || !Double.isFinite(pitch) || pitch <= 0.0
                || !Double.isFinite(radius) || (radius < 0.0 && radius != -1.0 && radius != -2.0)) return null;
        return new SoundSpec(sound, (float) volume, (float) pitch, delay, radius,
                stringIgnoreCase(source, "category", stringIgnoreCase(defaults, "category", "RECORDS")), "", "", false);
    }

    private boolean eject(Player player, Jukebox jukebox, ItemStack record, String id) {
        Location origin = jukebox.getLocation();
        jukebox.setRecord(new ItemStack(Material.AIR));
        if (!jukebox.update(true)) {
            jukebox.setRecord(record);
            jukebox.update(true);
            Text.send(player, configs.prefix(), "&cThe jukebox could not eject that disc.");
            return true;
        }
        stopAt(origin, id, section(id));
        player.getWorld().dropItemNaturally(origin, record.clone());
        return true;
    }

    private void stopAt(Location origin, String id, ConfigurationSection disc) {
        ActiveDisc active = activeJukeboxes.remove(locationKey(origin));
        List<String> keys = active == null ? specs(disc).stream().map(SoundSpec::key).toList() : active.soundKeys();
        double radius = active == null ? maxRadius(specs(disc)) : active.radius();
        for (String key : keys) engine.stopAround(origin, radius, key);
        if (active != null) {
            Player owner = org.bukkit.Bukkit.getPlayer(active.owner());
            if (owner != null) nbs.stop(owner);
        }
    }

    private static double maxRadius(List<SoundSpec> specs) {
        double radius = 0.0;
        for (SoundSpec spec : specs) radius = mergeRadius(radius, spec.radius());
        return radius;
    }

    private static double mergeRadius(double current, double candidate) {
        if (current == -1.0 || candidate == -1.0) return -1.0;
        if (current == -2.0 || candidate == -2.0) return -2.0;
        return Math.max(current, candidate);
    }

    private ConfigurationSection section(String id) {
        ConfigurationSection root = configs.discs().getConfigurationSection("discs");
        if (root == null || id == null) return null;
        for (String key : root.getKeys(false)) if (key.equalsIgnoreCase(id) && !key.equalsIgnoreCase("Version")) {
            ConfigurationSection disc = root.getConfigurationSection(key);
            return disc != null && disc.getBoolean("enabled", false) ? disc : null;
        }
        return null;
    }

    void onQuit(Player player) { nbs.onQuit(player); }

    private String rawDiscId(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer().get(discKey, PersistentDataType.STRING);
        if (id != null && !id.isBlank()) return id;
        if (meta.hasLore() && meta.getLore() != null) {
            for (String line : meta.getLore()) {
                String stripped = org.bukkit.ChatColor.stripColor(line);
                if (stripped != null && stripped.startsWith("KaltraSounds Disc: ")) {
                    String legacyId = stripped.substring("KaltraSounds Disc: ".length());
                    return legacyId.isBlank() ? null : legacyId;
                }
            }
        }
        return null;
    }

    private static void stopVanillaJukebox(Jukebox jukebox) {
        try {
            jukebox.getClass().getMethod("stopPlaying").invoke(jukebox);
        } catch (ReflectiveOperationException ignored) {
            // Older-compatible Jukebox implementations may not expose this method.
        }
    }

    private static void applyGlint(ItemMeta meta) {
        try {
            Method method = meta.getClass().getMethod("setEnchantmentGlintOverride", Boolean.class);
            method.invoke(meta, Boolean.TRUE);
        } catch (ReflectiveOperationException ignored) {
            // Glint is cosmetic. Unsupported API versions still receive a fully functional disc.
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

    private static String locationKey(Location location) {
        return (location.getWorld() == null ? "null" : location.getWorld().getUID()) + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private record PlaybackResult(boolean success, String message, List<String> soundKeys, double radius) {}
    private record ActiveDisc(String id, List<String> soundKeys, double radius, java.util.UUID owner, Location origin) {}
}
