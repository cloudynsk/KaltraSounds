package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class NbsModule {
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_STRING_BYTES = 1_048_576;
    private static final int MAX_NOTES = 2_000_000;
    private static final int MAX_POSITION = 50_000_000;
    private static final String[] VANILLA = {
            "block.note_block.harp", "block.note_block.bass", "block.note_block.basedrum", "block.note_block.snare",
            "block.note_block.hat", "block.note_block.guitar", "block.note_block.flute", "block.note_block.bell",
            "block.note_block.chime", "block.note_block.xylophone", "block.note_block.iron_xylophone",
            "block.note_block.cow_bell", "block.note_block.didgeridoo", "block.note_block.bit",
            "block.note_block.banjo", "block.note_block.pling"
    };

    private final JavaPlugin plugin;
    private final ConfigHub configs;
    private final SoundEngine engine;
    private final Map<UUID, Playback> playing = new HashMap<>();

    NbsModule(JavaPlugin plugin, ConfigHub configs, SoundEngine engine) {
        this.plugin = plugin;
        this.configs = configs;
        this.engine = engine;
    }

    List<String> listSongs() {
        File[] files = configs.nbsFolder().listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".nbs"));
        if (files == null) return List.of();
        List<String> out = new ArrayList<>();
        for (File file : files) out.add(file.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    String play(Player player, String name) {
        if (!configs.module("nbs-player")) return "&cThe NBS module is disabled.";
        File file = resolve(name);
        if (file == null) return "&cNBS file not found.";
        try {
            Song song = readValidated(file);
            stop(player);
            Playback playback = new Playback(player, song);
            playing.put(player.getUniqueId(), playback);
            playback.task = Bukkit.getScheduler().runTaskTimer(plugin, playback::tick, 0L, 1L);
            return "&aPlaying &f" + file.getName() + "&a.";
        } catch (IOException e) {
            plugin.getLogger().warning("Could not load NBS file " + file.getName() + ": " + e.getMessage());
            return "&cCould not read that NBS file: " + e.getMessage();
        }
    }

    String stop(Player player) {
        Playback playback = playing.remove(player.getUniqueId());
        if (playback == null) return "&eNo NBS song is playing.";
        if (playback.task != null) playback.task.cancel();
        return "&aStopped NBS playback.";
    }

    String pause(Player player) {
        Playback playback = playing.get(player.getUniqueId());
        if (playback == null) return "&eNo NBS song is playing.";
        playback.paused = true;
        return "&eNBS playback paused.";
    }

    String resume(Player player) {
        Playback playback = playing.get(player.getUniqueId());
        if (playback == null) return "&eNo NBS song is playing.";
        playback.paused = false;
        return "&aNBS playback resumed.";
    }

    void onQuit(Player player) {
        Playback playback = playing.remove(player.getUniqueId());
        if (playback != null && playback.task != null) playback.task.cancel();
    }

    void stopAll() {
        for (Playback playback : playing.values()) if (playback.task != null) playback.task.cancel();
        playing.clear();
    }

    private File resolve(String name) {
        String requested = name.toLowerCase(Locale.ROOT).endsWith(".nbs") ? name : name + ".nbs";
        File file = new File(configs.nbsFolder(), requested);
        try {
            File canonicalFolder = configs.nbsFolder().getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            if (!canonicalFile.getPath().startsWith(canonicalFolder.getPath() + File.separator)) return null;
            return canonicalFile.isFile() ? canonicalFile : null;
        } catch (IOException e) {
            return null;
        }
    }

    private final class Playback {
        private final Player player;
        private final Song song;
        private BukkitTask task;
        private double songTick = -1;
        private int noteIndex = 0;
        private boolean paused;
        private int loops;

        private Playback(Player player, Song song) {
            this.player = player;
            this.song = song;
        }

        private void tick() {
            if (!player.isOnline()) {
                stop(player);
                return;
            }
            if (paused) return;
            songTick += song.tempo / 20.0;
            while (noteIndex < song.notes.size() && song.notes.get(noteIndex).tick <= songTick) {
                Note note = song.notes.get(noteIndex++);
                String sound = note.instrument < VANILLA.length ? VANILLA[note.instrument] : song.customInstruments.getOrDefault(note.instrument, VANILLA[0]);
                float pitch = (float) Math.pow(2.0, ((note.key - 45.0) / 12.0) + (note.finePitch / 1200.0));
                float volume = Math.max(0.0f, Math.min(1.0f, note.velocity / 100.0f));
                engine.playDirect(player, sound, volume, pitch);
            }
            if (noteIndex >= song.notes.size() && songTick > song.length + 1) {
                if (song.loop && (song.maxLoops == 0 || loops < song.maxLoops)) {
                    loops++;
                    songTick = song.loopStart - 1;
                    noteIndex = 0;
                    while (noteIndex < song.notes.size() && song.notes.get(noteIndex).tick < song.loopStart) noteIndex++;
                } else stop(player);
            }
        }
    }

    private record Note(int tick, int layer, int instrument, int key, int velocity, int panning, int finePitch) {}
    private record Song(int length, double tempo, boolean loop, int maxLoops, int loopStart, List<Note> notes, Map<Integer, String> customInstruments) {}

    static Song readValidated(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("NBS file does not exist");
        if (file.length() > MAX_FILE_BYTES) throw new IOException("NBS file exceeds 64 MiB");
        return read(file);
    }

    static Song read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int firstLength = readUShortLE(in);
            int version;
            int vanillaCount = 10;
            int length;
            int layers;
            if (firstLength == 0) {
                version = in.readUnsignedByte();
                if (version < 1 || version > 5) throw new IOException("Unsupported NBS version: " + version);
                vanillaCount = in.readUnsignedByte();
                length = version >= 3 ? readUShortLE(in) : 0;
                layers = readUShortLE(in);
            } else {
                version = 0;
                length = firstLength;
                layers = readUShortLE(in);
            }
            readString(in); // name
            readString(in); // author
            readString(in); // original author
            readString(in); // description
            double tempo = readUShortLE(in) / 100.0;
            if (tempo <= 0) tempo = 10.0;
            in.readUnsignedByte(); // autosave
            in.readUnsignedByte(); // autosave duration
            in.readUnsignedByte(); // time signature
            readIntLE(in); readIntLE(in); readIntLE(in); readIntLE(in); readIntLE(in);
            readString(in); // imported filename
            boolean loop = false;
            int maxLoops = 0;
            int loopStart = 0;
            if (version >= 4) {
                loop = in.readUnsignedByte() != 0;
                maxLoops = in.readUnsignedByte();
                loopStart = readUShortLE(in);
            }

            List<Note> notes = new ArrayList<>();
            int tick = -1;
            while (true) {
                int jumpTicks = readUShortLE(in);
                if (jumpTicks == 0) break;
                long nextTick = (long) tick + jumpTicks;
                if (nextTick > MAX_POSITION) throw new IOException("NBS tick position exceeds " + MAX_POSITION);
                tick = (int) nextTick;
                int layer = -1;
                while (true) {
                    int jumpLayers = readUShortLE(in);
                    if (jumpLayers == 0) break;
                    long nextLayer = (long) layer + jumpLayers;
                    if (nextLayer > MAX_POSITION) throw new IOException("NBS layer position exceeds " + MAX_POSITION);
                    layer = (int) nextLayer;
                    int instrument = in.readUnsignedByte();
                    int key = in.readUnsignedByte();
                    int velocity = 100;
                    int panning = 100;
                    int finePitch = 0;
                    if (version >= 4) {
                        velocity = in.readUnsignedByte();
                        panning = in.readUnsignedByte();
                        finePitch = readShortLE(in);
                    }
                    if (notes.size() >= MAX_NOTES) throw new IOException("NBS note count exceeds " + MAX_NOTES);
                    notes.add(new Note(tick, layer, instrument, key, velocity, panning, finePitch));
                }
            }

            for (int i = 0; i < layers; i++) {
                readString(in);
                if (version >= 4) in.readUnsignedByte();
                in.readUnsignedByte();
                if (version >= 2) in.readUnsignedByte();
            }

            Map<Integer, String> custom = new HashMap<>();
            try {
                int count = in.readUnsignedByte();
                for (int i = 0; i < count; i++) {
                    readString(in);
                    String soundFile = readString(in);
                    in.readUnsignedByte();
                    in.readUnsignedByte();
                    String sound = normalizeCustomSoundKey(soundFile);
                    if (sound != null) custom.put(vanillaCount + i, sound);
                }
            } catch (EOFException ignored) {
            }
            if (notes.isEmpty()) throw new IOException("NBS file contains no notes");
            notes.sort(Comparator.comparingInt(Note::tick).thenComparingInt(Note::layer));
            int effectiveLength = Math.max(length, notes.get(notes.size() - 1).tick());
            if (loopStart < 0 || loopStart > effectiveLength) throw new IOException("NBS loop start is outside the song length");
            return new Song(effectiveLength, tempo, loop, maxLoops, loopStart, notes, custom);
        }
    }

    private static String normalizeCustomSoundKey(String raw) {
        if (raw == null) return null;
        String sound = raw.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (sound.isEmpty()) return null;
        int dot = sound.lastIndexOf('.');
        if (dot > sound.lastIndexOf('/')) sound = sound.substring(0, dot);

        int colon = sound.indexOf(':');
        boolean explicitKey = colon > 0 && colon < sound.length() - 1
                && sound.indexOf('/') > colon
                && sound.charAt(colon + 1) != '/';
        if (!explicitKey) {
            int slash = sound.lastIndexOf('/');
            if (slash >= 0) sound = sound.substring(slash + 1);
        }

        StringBuilder cleaned = new StringBuilder(sound.length());
        for (int i = 0; i < sound.length(); i++) {
            char c = sound.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-' || c == ':') {
                cleaned.append(c);
            } else {
                cleaned.append('_');
            }
        }
        String key = cleaned.toString();
        if (key.isBlank()) return null;
        return key.indexOf(':') >= 0 ? key : "minecraft:" + key;
    }

    private static int readUShortLE(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte();
        return a | (b << 8);
    }

    private static short readShortLE(DataInputStream in) throws IOException {
        return (short) readUShortLE(in);
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte(), c = in.readUnsignedByte(), d = in.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readIntLE(in);
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid NBS string length: " + length);
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
