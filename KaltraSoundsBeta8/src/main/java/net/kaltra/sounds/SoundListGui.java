package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

final class SoundListGui {
    private static final String TITLE_PREFIX = "KaltraSounds · ";
    private final ConfigHub configs;
    private final SoundEngine engine;
    private final NamespacedKey soundKey;
    private final NamespacedKey actionKey;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private record Session(Inventory inventory, int page, String search) {}

    SoundListGui(JavaPlugin plugin, ConfigHub configs, SoundEngine engine) {
        this.configs = configs;
        this.engine = engine;
        this.soundKey = new NamespacedKey(plugin, "preview_sound");
        this.actionKey = new NamespacedKey(plugin, "gui_action");
    }

    void open(Player player, int page, String search) {
        List<String> sounds = sounds(search);
        int rows = configs.guiRows();
        int size = rows * 9;
        int content = size - 9;
        int pages = Math.max(1, (sounds.size() + content - 1) / content);
        int actual = Math.max(1, Math.min(pages, page));
        Inventory inventory = Bukkit.createInventory(null, size, TITLE_PREFIX + actual + "/" + pages);
        int from = (actual - 1) * content;
        int to = Math.min(sounds.size(), from + content);
        for (int i = from; i < to; i++) inventory.setItem(i - from, soundItem(sounds.get(i)));
        if (actual > 1) inventory.setItem(size - 9, actionItem(material("SPECTRAL_ARROW", Material.FEATHER), "&ePrevious page", "previous"));
        inventory.setItem(size - 5, actionItem(material("BARRIER", Material.FEATHER), "&cStop all sounds", "stop"));
        if (actual < pages) inventory.setItem(size - 1, actionItem(material("SPECTRAL_ARROW", Material.FEATHER), "&eNext page", "next"));
        sessions.put(player.getUniqueId(), new Session(inventory, actual, search == null ? "" : search));
        player.openInventory(inventory);
    }

    boolean handle(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return false;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory()) return false;
        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return true;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return true;
        String sound = meta.getPersistentDataContainer().get(soundKey, PersistentDataType.STRING);
        if (sound != null) {
            boolean played = engine.playDirect(player, sound, 1.0f, 1.0f);
            if (!played) Text.send(player, configs.prefix(), "&cCould not preview &f" + sound + "&c.");
            return true;
        }
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return true;
        int current = session.page();
        switch (action) {
            case "previous" -> open(player, current - 1, session.search());
            case "next" -> open(player, current + 1, session.search());
            case "stop" -> engine.stopAll(player);
        }
        return true;
    }

    List<String> sounds(String search) {
        Set<String> keys = new LinkedHashSet<>();
        collectRegisteredSounds(keys);
        collectConfigured(configs.events(), keys);
        for (var yaml : configs.situationalFiles().values()) collectConfigured(yaml, keys);
        String needle = search == null ? "" : search.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String key : keys) if (needle.isBlank() || key.toLowerCase(Locale.ROOT).contains(needle)) out.add(key);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private void collectRegisteredSounds(Set<String> keys) {
        boolean registryLoaded = false;
        try {
            Class<?> registryClass = Class.forName("org.bukkit.Registry");
            Object registry;
            try {
                registry = registryClass.getField("SOUND_EVENT").get(null);
            } catch (NoSuchFieldException ignored) {
                registry = registryClass.getField("SOUNDS").get(null);
            }
            if (registry instanceof Iterable<?> iterable) {
                registryLoaded = true;
                for (Object sound : iterable) {
                    try {
                        Object key = sound.getClass().getMethod("getKey").invoke(sound);
                        if (key != null) keys.add(String.valueOf(key));
                    } catch (ReflectiveOperationException ignored) {
                        keys.add(String.valueOf(sound));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (registryLoaded) return;

        try {
            Class<?> soundClass = Class.forName("org.bukkit.Sound");
            for (java.lang.reflect.Field field : soundClass.getFields()) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        && soundClass.isAssignableFrom(field.getType())) {
                    keys.add(field.getName());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void collectConfigured(ConfigurationSection section, Set<String> keys) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) continue;
            ConfigurationSection sounds = child.getConfigurationSection("Sounds");
            if (sounds != null) for (String id : sounds.getKeys(false)) {
                String sound = sounds.getString(id + ".Sound", "");
                if (!sound.isBlank()) keys.add(sound);
            }
            collectConfigured(child, keys);
        }
    }

    void handleClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session != null && event.getInventory() == session.inventory()) sessions.remove(player.getUniqueId());
    }

    void onQuit(Player player) { sessions.remove(player.getUniqueId()); }

    private ItemStack soundItem(String sound) {
        String family = SoundIconResolver.family(sound);
        ItemStack item = new ItemStack(iconFor(sound));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color("&b" + sound));
            meta.setLore(List.of(
                    Text.color("&7Category: &f" + family),
                    Text.color("&7Click to preview.")
            ));
            meta.getPersistentDataContainer().set(soundKey, PersistentDataType.STRING, sound);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material iconFor(String sound) {
        for (String candidate : SoundIconResolver.candidates(sound)) {
            Material found = Material.matchMaterial(candidate);
            if (found != null && found != Material.AIR && found.isItem()) return found;
        }
        return Material.NOTE_BLOCK;
    }

    private static Material material(String name, Material fallback) {
        Material found = Material.matchMaterial(name);
        return found == null || found == Material.AIR || !found.isItem() ? fallback : found;
    }

    private ItemStack actionItem(Material material, String name, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

}
