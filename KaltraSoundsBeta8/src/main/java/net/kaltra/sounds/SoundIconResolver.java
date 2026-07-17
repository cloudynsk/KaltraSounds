package net.kaltra.sounds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure registry-key parser used by the GUI. It returns Material enum names,
 * leaving runtime availability checks to Bukkit's Material.matchMaterial. */
final class SoundIconResolver {
    private static final Set<String> BLOCK_ACTIONS = Set.of(
            "break", "broken", "fall", "hit", "place", "step", "open", "close", "click", "on", "off",
            "power", "depower", "use", "activate", "deactivate", "slide", "lock", "unlock", "charge",
            "ambient", "resonate", "chime", "fail", "success", "land", "jump", "drip", "extinguish"
    );
    private static final Set<String> ENTITY_ACTIONS = Set.of(
            "ambient", "attack", "celebrate", "convert", "death", "drink", "eat", "flap", "fly", "growl",
            "hurt", "idle", "jump", "land", "lay_egg", "milked", "pant", "roar", "shoot", "splash", "step",
            "swim", "teleport", "warning", "whine", "work", "yes", "no"
    );

    private SoundIconResolver() {}

    static List<String> candidates(String soundKey) {
        String path = normalizePath(soundKey);
        if (path.isBlank()) return List.of("NOTE_BLOCK");
        String[] parts = path.split("\\.");
        LinkedHashSet<String> out = new LinkedHashSet<>();

        if (parts.length >= 2 && parts[0].equals("music_disc")) {
            add(out, "MUSIC_DISC_" + parts[1]);
        } else if (parts.length >= 2 && parts[0].equals("block")) {
            addBlockCandidates(out, parts);
        } else if (parts.length >= 2 && parts[0].equals("item")) {
            addItemCandidates(out, parts);
        } else if (parts.length >= 2 && parts[0].equals("entity")) {
            addEntityCandidates(out, parts[1]);
        } else {
            addLegacyCandidates(out, path);
        }

        addCuratedFallbacks(out, path);
        add(out, "NOTE_BLOCK");
        return List.copyOf(out);
    }

    static String family(String soundKey) {
        String path = normalizePath(soundKey);
        if (path.startsWith("entity.")) return "Entity";
        if (path.startsWith("block.")) return "Block";
        if (path.startsWith("item.")) return "Item";
        if (path.startsWith("music") || path.startsWith("music_disc.") || path.startsWith("block.note_block.")) return "Music";
        if (path.startsWith("ambient.")) return "Ambient";
        if (path.startsWith("weather.")) return "Weather";
        if (path.startsWith("ui.")) return "Interface";
        return soundKey != null && soundKey.contains(":") && !soundKey.toLowerCase(Locale.ROOT).startsWith("minecraft:") ? "Custom" : "General";
    }

    private static void addBlockCandidates(Set<String> out, String[] parts) {
        int end = parts.length - 1;
        if (BLOCK_ACTIONS.contains(parts[end])) end--;
        if (end < 1) return;
        String block = join(parts, 1, end + 1);
        add(out, block);
        if (block.endsWith("_DOOR") || block.endsWith("_TRAPDOOR") || block.endsWith("_FENCE_GATE")) add(out, block);
        if (block.equals("WOOD")) add(out, "OAK_LOG");
        if (block.equals("METAL")) add(out, "IRON_BLOCK");
        if (block.equals("WOOL")) add(out, "WHITE_WOOL");
        if (block.equals("SAND")) add(out, "SAND");
        if (block.equals("GRAVEL")) add(out, "GRAVEL");
    }

    private static void addItemCandidates(Set<String> out, String[] parts) {
        String item = parts[1];
        add(out, item);
        if (parts.length >= 3 && parts[1].equals("armor") && parts[2].startsWith("equip_")) {
            String material = parts[2].substring("equip_".length());
            add(out, material + "_CHESTPLATE");
        }
        if (parts.length >= 3 && parts[1].equals("bucket")) add(out, parts[2] + "_BUCKET");
    }

    private static void addEntityCandidates(Set<String> out, String entity) {
        add(out, entity + "_SPAWN_EGG");
        switch (entity) {
            case "player" -> add(out, "PLAYER_HEAD");
            case "zombie", "husk", "drowned", "zombie_villager" -> add(out, "ZOMBIE_HEAD");
            case "skeleton", "stray", "bogged", "wither_skeleton" -> add(out, entity.equals("wither_skeleton") ? "WITHER_SKELETON_SKULL" : "SKELETON_SKULL");
            case "creeper" -> add(out, "CREEPER_HEAD");
            case "ender_dragon" -> add(out, "DRAGON_HEAD");
            case "piglin" -> add(out, "PIGLIN_HEAD");
            default -> { }
        }
    }

    private static void addLegacyCandidates(Set<String> out, String path) {
        String constant = path.toUpperCase(Locale.ROOT).replace('.', '_');
        if (constant.startsWith("MUSIC_DISC_")) add(out, constant);
        if (constant.startsWith("BLOCK_")) {
            String body = constant.substring("BLOCK_".length());
            for (String action : BLOCK_ACTIONS) {
                String suffix = "_" + action.toUpperCase(Locale.ROOT);
                if (body.endsWith(suffix)) {
                    add(out, body.substring(0, body.length() - suffix.length()));
                    return;
                }
            }
        }
        if (constant.startsWith("ENTITY_")) {
            String body = constant.substring("ENTITY_".length());
            for (String action : ENTITY_ACTIONS) {
                String suffix = "_" + action.toUpperCase(Locale.ROOT);
                if (body.endsWith(suffix)) {
                    addEntityCandidates(out, body.substring(0, body.length() - suffix.length()).toLowerCase(Locale.ROOT));
                    return;
                }
            }
        }
        if (constant.startsWith("ITEM_")) add(out, constant.substring("ITEM_".length()));
    }

    private static void addCuratedFallbacks(Set<String> out, String path) {
        Set<String> tokens = tokenSet(path);

        // Dimension and biome identity must be resolved before broad ambient fallbacks.
        // Otherwise every ambient Nether sound ends up looking like sculk, which is
        // technically an icon and practically nonsense.
        if (path.contains("soul_sand_valley")) add(out, "SOUL_SAND");
        if (path.contains("crimson_forest") || tokens.contains("crimson")) add(out, "CRIMSON_NYLIUM");
        if (path.contains("warped_forest") || tokens.contains("warped")) add(out, "WARPED_NYLIUM");
        if (path.contains("basalt_deltas") || tokens.contains("basalt")) add(out, "BASALT");
        if (path.contains("nether_wastes") || tokens.contains("nether")) add(out, "NETHERRACK");
        if (path.contains("the_end") || path.contains("end_highlands") || path.contains("end_midlands")
                || path.contains("small_end_islands") || path.equals("music.end")) add(out, "END_STONE");

        if (hasAny(tokens, "experience", "levelup", "orb")) add(out, "EXPERIENCE_BOTTLE");
        if (hasAny(tokens, "teleport", "portal", "enderman")) add(out, "ENDER_PEARL");
        if (hasAny(tokens, "attack", "hurt", "damage", "sword", "weapon", "parry")) add(out, "IRON_SWORD");
        if (hasAny(tokens, "bow", "crossbow", "arrow", "trident")) add(out, "BOW");
        if (hasAny(tokens, "inventory", "container", "chest", "barrel", "shulker")) add(out, "CHEST");
        if (hasAny(tokens, "water", "swim", "splash", "bubble", "ocean")) add(out, "WATER_BUCKET");
        if (hasAny(tokens, "lava", "fire", "flame", "blaze", "burn")) add(out, "BLAZE_POWDER");
        if (hasAny(tokens, "rain", "thunder", "lightning", "weather")) add(out, "LIGHTNING_ROD");
        if (hasAny(tokens, "wind", "fly", "flight", "glide")) add(out, "ELYTRA");
        if (hasAny(tokens, "ui", "button", "click", "select")) add(out, "STONE_BUTTON");
        if (hasAny(tokens, "note", "music", "jukebox", "record", "disc")) add(out, "NOTE_BLOCK");
        if (hasAny(tokens, "warden", "sculk", "shriek", "sensor")) add(out, "SCULK");
        if (hasAny(tokens, "cave")) add(out, "STONE");
        if (hasAny(tokens, "eat", "drink", "food")) add(out, "APPLE");
        if (hasAny(tokens, "book", "page", "enchant")) add(out, "BOOK");
    }

    private static String normalizePath(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        int colon = normalized.indexOf(':');
        if (colon >= 0) normalized = normalized.substring(colon + 1);
        return normalized;
    }

    private static Set<String> tokenSet(String path) {
        Set<String> out = new LinkedHashSet<>();
        for (String dotted : path.split("[.:/]")) {
            for (String token : dotted.split("_")) if (!token.isBlank()) out.add(token);
        }
        return out;
    }

    private static boolean hasAny(Set<String> tokens, String... values) {
        for (String value : values) if (tokens.contains(value)) return true;
        return false;
    }

    private static void add(Set<String> out, String value) {
        if (value == null) return;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        if (!normalized.isBlank()) out.add(normalized);
    }

    private static String join(String[] values, int from, int to) {
        List<String> selected = new ArrayList<>();
        for (int i = from; i < to; i++) selected.add(values[i]);
        return String.join("_", selected);
    }
}
