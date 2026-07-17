package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class IntegrationModule {
    private final JavaPlugin plugin;
    private final ConfigHub configs;
    private final SoundEngine engine;
    private final SituationalModule situational;
    private final Map<String, String> status = new LinkedHashMap<>();
    private final List<Listener> dynamicListeners = new ArrayList<>();
    private final Map<UUID, Boolean> pendingAuthJoins = new HashMap<>();
    private final Set<UUID> recentRegistrations = new HashSet<>();
    private Object packetListener;
    private Object packetEventManager;
    private boolean externalChatProvider;
    private boolean authJoinGate;
    private boolean afkProvider;
    private boolean godProvider;
    private boolean vanishProvider;

    IntegrationModule(JavaPlugin plugin, ConfigHub configs, SoundEngine engine, SituationalModule situational) {
        this.plugin = plugin;
        this.configs = configs;
        this.engine = engine;
        this.situational = situational;
    }

    void initialize() {
        stop();
        status.clear();
        externalChatProvider = false;
        if (configs.integration("venture-chat")) {
            externalChatProvider = registerChatEvent("VentureChat", "mineverse.Aust1n46.chat.api.events.VentureChatEvent", true);
        } else status.put("VentureChat", "disabled");
        if (configs.integration("chat-reaction")) {
            registerChatEvent("ChatReaction", "me.clip.chatreaction.events.ReactionWinEvent", false);
        } else status.put("ChatReaction", "disabled");
        detectConfiguredPlugin("EssentialsChat", "essentials-chat", true);
        detectConfiguredPlugin("TownyChat", "towny-chat", true);
        detectConfiguredPlugin("ChannelsHandler", "channels-handler", true);
        registerEssentials();
        registerAuthMe();
        registerCmi();
        registerSuperVanish();
        registerPacketEvents();
        detect("WorldGuard", "com.sk89q.worldguard.WorldGuard");
        detect("RedProtect", "br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect");
    }

    Map<String, String> status() { return new LinkedHashMap<>(status); }

    boolean handlesGenericChat() { return externalChatProvider; }

    boolean deferJoinSound(Player player) {
        if (!authJoinGate) return false;
        pendingAuthJoins.put(player.getUniqueId(), !player.hasPlayedBefore());
        return true;
    }

    void onQuit(Player player) {
        pendingAuthJoins.remove(player.getUniqueId());
        recentRegistrations.remove(player.getUniqueId());
    }

    void stop() {
        for (Listener listener : dynamicListeners) HandlerList.unregisterAll(listener);
        dynamicListeners.clear();
        externalChatProvider = false;
        authJoinGate = false;
        afkProvider = false;
        godProvider = false;
        vanishProvider = false;
        if (packetListener != null && packetEventManager != null) {
            try {
                for (Method method : packetEventManager.getClass().getMethods()) {
                    if (method.getName().equals("unregisterListener") && method.getParameterCount() == 1) {
                        method.invoke(packetEventManager, packetListener);
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        packetListener = null;
        packetEventManager = null;
    }

    private void registerEssentials() {
        if (!configs.integration("essentials")) {
            status.put("Essentials", "disabled");
            return;
        }
        int hooked = 0;
        if (registerStatusEvent("net.ess3.api.events.AfkStatusChangeEvent", "AFK On", "AFK Off")) {
            afkProvider = true;
            hooked++;
        }
        if (registerStatusEvent("net.ess3.api.events.GodStatusChangeEvent", "God Mode On", "God Mode Off")) {
            godProvider = true;
            hooked++;
        }
        if (registerStatusEvent("net.ess3.api.events.VanishStatusChangeEvent", "Vanish On", "Vanish Off")) {
            vanishProvider = true;
            hooked++;
        }
        status.put("Essentials", hooked == 0 ? pluginState("Essentials") : "hooked (" + hooked + "/3 status events)");
    }

    private void registerCmi() {
        if (!configs.integration("cmi")) {
            status.put("CMI", "disabled");
            return;
        }
        int hooked = 0;
        if (!afkProvider) {
            boolean enter = registerPlayerEvent("com.Zrips.CMI.events.CMIAfkEnterEvent", "AFK On");
            boolean leave = registerPlayerEvent("com.Zrips.CMI.events.CMIAfkLeaveEvent", "AFK Off");
            if (enter || leave) {
                afkProvider = true;
                hooked += (enter ? 1 : 0) + (leave ? 1 : 0);
            }
        }
        if (!vanishProvider) {
            boolean vanish = registerPlayerEvent("com.Zrips.CMI.events.CMIPlayerVanishEvent", "Vanish On");
            boolean unvanish = registerPlayerEvent("com.Zrips.CMI.events.CMIPlayerUnVanishEvent", "Vanish Off");
            if (vanish || unvanish) {
                vanishProvider = true;
                hooked += (vanish ? 1 : 0) + (unvanish ? 1 : 0);
            }
        }
        if (hooked > 0) status.put("CMI", "hooked (" + hooked + " event" + (hooked == 1 ? "" : "s") + ")");
        else if (Bukkit.getPluginManager().getPlugin("CMI") != null && (afkProvider || vanishProvider)) status.put("CMI", "suppressed by earlier AFK/vanish providers");
        else status.put("CMI", pluginState("CMI"));
    }

    private void registerSuperVanish() {
        if (!configs.integration("supervanish")) {
            status.put("SuperVanish/PremiumVanish", "disabled");
            return;
        }
        boolean installed = Bukkit.getPluginManager().getPlugin("SuperVanish") != null
                || Bukkit.getPluginManager().getPlugin("PremiumVanish") != null;
        if (!installed) {
            status.put("SuperVanish/PremiumVanish", "not installed");
            return;
        }
        if (vanishProvider) {
            status.put("SuperVanish/PremiumVanish", "suppressed by earlier vanish provider");
            return;
        }
        boolean hooked = registerSimpleEvent("de.myzelyam.api.vanish.PlayerVanishStateChangeEvent", event -> {
            UUID id = extractUuid(event, "getUUID", "getUniqueId");
            Boolean vanishing = extractBoolean(event, "isVanishing");
            Player player = id == null ? null : Bukkit.getPlayer(id);
            if (player != null && vanishing != null) playVanish(player, vanishing);
        });
        vanishProvider = hooked;
        status.put("SuperVanish/PremiumVanish", hooked ? "hooked" : "detected, event hook unavailable");
    }

    private boolean registerPlayerEvent(String className, String eventName) {
        return registerSimpleEvent(className, event -> {
            Player player = extractPlayer(event);
            if (player != null) playStatus(player, eventName);
        });
    }

    private void registerAuthMe() {
        if (!configs.integration("authme")) {
            status.put("AuthMe", "disabled");
            return;
        }
        boolean login = registerSimpleEvent("fr.xephi.authme.events.LoginEvent", event -> {
            Player player = extractPlayer(event);
            if (player == null) return;
            Boolean first = pendingAuthJoins.remove(player.getUniqueId());
            if (first != null) engine.playEvent(first ? "First Join" : "Join Server", player, player.getLocation());
            recentRegistrations.remove(player.getUniqueId());
        });
        boolean register = registerSimpleEvent("fr.xephi.authme.events.RegisterEvent", event -> {
            Player player = extractPlayer(event);
            if (player == null || !recentRegistrations.add(player.getUniqueId())) return;
            engine.playEvent("Auth Register", player, player.getLocation());
        });
        authJoinGate = login;
        if (!authJoinGate && !pendingAuthJoins.isEmpty()) {
            for (Map.Entry<UUID, Boolean> entry : new ArrayList<>(pendingAuthJoins.entrySet())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) engine.playEvent(entry.getValue() ? "First Join" : "Join Server", player, player.getLocation());
            }
            pendingAuthJoins.clear();
        }
        status.put("AuthMe", login ? (register ? "hooked (login gate + register)" : "hooked (login gate)") : pluginState("AuthMe", "AuthMeReloaded"));
    }

    @SuppressWarnings("unchecked")
    private boolean registerStatusEvent(String className, String onEvent, String offEvent) {
        return registerSimpleEvent(className, event -> {
            Player player = extractAffectedPlayer(event);
            Boolean enabled = extractBoolean(event, "getValue", "isEnabled", "isOn");
            if (player == null || enabled == null) return;
            playStatus(player, enabled ? onEvent : offEvent);
        });
    }

    private void playStatus(Player player, String eventName) {
        if (eventName.equals("Vanish On")) {
            playVanish(player, true);
            return;
        }
        if (eventName.equals("Vanish Off")) {
            playVanish(player, false);
            return;
        }
        boolean played = engine.playEvent(eventName, player, player.getLocation());
        if (played) return;
        if (eventName.equals("AFK On") || eventName.equals("AFK Off")) {
            engine.playEvent("Afk Toggle", player, player.getLocation());
        } else if (eventName.equals("God Mode On") || eventName.equals("God Mode Off")) {
            engine.playEvent("God Toggle", player, player.getLocation());
        }
    }

    private void playVanish(Player player, boolean vanishing) {
        String splitRule = vanishing ? "Vanish On" : "Vanish Off";
        boolean played = engine.playEvent(splitRule, player, player.getLocation());
        ConfigurationSection legacy = configs.eventRule("Vanish Toggle");
        if (!played && legacy != null && legacy.getBoolean("Enabled", false)) {
            engine.playEvent("Vanish Toggle", player, player.getLocation());
        }
        if (legacy == null) return;
        if (vanishing && (legacy.getBoolean("Play Leave Sound", false)
                || legacy.getBoolean("Play Leave Server", false))) {
            engine.playEvent("Leave Server", player, player.getLocation());
        } else if (!vanishing && (legacy.getBoolean("Play Join Sound", false)
                || legacy.getBoolean("Play Join Server", false))) {
            engine.playEvent("Join Server", player, player.getLocation());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean registerSimpleEvent(String className, java.util.function.Consumer<Object> handler) {
        try {
            Class<?> raw = Class.forName(className);
            if (!Event.class.isAssignableFrom(raw)) return false;
            Listener listener = new Listener() {};
            EventExecutor executor = (ignored, event) -> Bukkit.getScheduler().runTask(plugin, () -> handler.accept(event));
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) raw, listener, EventPriority.MONITOR, executor, plugin, true);
            dynamicListeners.add(listener);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not hook " + className + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    private Player extractAffectedPlayer(Object event) {
        try {
            Object affected = event.getClass().getMethod("getAffected").invoke(event);
            if (affected instanceof Player player) return player;
            if (affected != null) {
                Object base = affected.getClass().getMethod("getBase").invoke(affected);
                if (base instanceof Player player) return player;
            }
        } catch (Throwable ignored) {
        }
        return extractPlayer(event);
    }

    private UUID extractUuid(Object source, String... names) {
        for (String name : names) {
            try {
                Object value = source.getClass().getMethod(name).invoke(source);
                if (value instanceof UUID uuid) return uuid;
                if (value != null) return UUID.fromString(String.valueOf(value));
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private Boolean extractBoolean(Object source, String... names) {
        for (String name : names) {
            try {
                Object value = source.getClass().getMethod(name).invoke(source);
                if (value instanceof Boolean bool) return bool;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String pluginState(String... names) {
        for (String name : names) if (Bukkit.getPluginManager().getPlugin(name) != null) return "detected, hook unavailable";
        return "not installed";
    }

    private void detectPlugin(String name) {
        try {
            Object found = Bukkit.getPluginManager().getPlugin(name);
            status.put(name, found == null ? "not installed" : "detected (no dedicated hook)");
        } catch (Throwable e) {
            status.put(name, "detection failed");
        }
    }

    private void detectConfiguredPlugin(String name, String configKey, boolean chatRecipientHook) {
        if (!configs.integration(configKey)) {
            status.put(name, "disabled");
            return;
        }
        try {
            Object found = Bukkit.getPluginManager().getPlugin(name);
            if (found == null) status.put(name, "not installed");
            else status.put(name, chatRecipientHook
                    ? "hooked through final Bukkit chat recipients"
                    : "detected (no dedicated hook)");
        } catch (Throwable e) {
            status.put(name, "detection failed");
        }
    }

    private void detect(String name, String className) {
        try {
            Class.forName(className);
            status.put(name, "detected");
        } catch (ClassNotFoundException e) {
            status.putIfAbsent(name, "not installed");
        }
    }

    @SuppressWarnings("unchecked")
    private boolean registerChatEvent(String name, String className, boolean chatProvider) {
        try {
            Class<?> raw = Class.forName(className);
            if (!Event.class.isAssignableFrom(raw)) {
                status.put(name, "detected, unsupported event type");
                return false;
            }
            Listener listener = new Listener() {};
            EventExecutor executor = (ignored, event) -> handleGenericChat(event, chatProvider);
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) raw, listener, EventPriority.MONITOR, executor, plugin, true);
            dynamicListeners.add(listener);
            status.put(name, chatProvider ? "hooked (generic chat suppressed)" : "hooked");
            return true;
        } catch (ClassNotFoundException e) {
            status.put(name, "not installed");
            return false;
        } catch (Throwable e) {
            status.put(name, "hook failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void handleGenericChat(Object event, boolean chatProvider) {
        Player player = extractPlayer(event);
        String message = extractString(event, "getMessage", "getOriginalMessage", "getText", "getReaction");
        if (player == null || message == null || message.isBlank()) return;
        List<Player> recipients = extractPlayers(event, "getRecipients", "getOnlineRecipients", "getAudience");
        // External channel providers must never fall back to an unrestricted radius when their
        // recipient API changes. Sender-only is the safe failure mode; leaking private channel
        // sounds to unrelated players is not.
        if (chatProvider && recipients == null) recipients = List.of(player);
        List<Player> recipientSnapshot = recipients == null ? null : List.copyOf(recipients);
        Boolean cancelled = extractBoolean(event, "isCancelled");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) situational.onChat(player, message, Boolean.TRUE.equals(cancelled), recipientSnapshot);
        });
    }

    private List<Player> extractPlayers(Object source, String... methods) {
        for (String methodName : methods) {
            try {
                Object value = source.getClass().getMethod(methodName).invoke(source);
                if (!(value instanceof Collection<?> collection)) continue;
                List<Player> players = new ArrayList<>();
                for (Object item : collection) {
                    if (item instanceof Player player) players.add(player);
                    else if (item != null) {
                        Player nested = extractPlayer(item);
                        if (nested != null) players.add(nested);
                    }
                }
                return players;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Player extractPlayer(Object source) {
        for (String methodName : List.of("getPlayer", "getSender", "getWinner")) {
            try {
                Object value = source.getClass().getMethod(methodName).invoke(source);
                if (value instanceof Player player) return player;
                if (value != null) {
                    try {
                        Object base = value.getClass().getMethod("getBase").invoke(value);
                        if (base instanceof Player player) return player;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String extractString(Object source, String... names) {
        for (String name : names) {
            try {
                Object value = source.getClass().getMethod(name).invoke(source);
                if (value != null) return String.valueOf(value);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void registerPacketEvents() {
        if (!configs.module("nature-sound-replacer") || !configs.integration("packet-events")
                || !configs.replacements().getBoolean("enabled", false)) {
            status.put("PacketEvents nature replacement", "disabled");
            return;
        }
        try {
            Class<?> packetEvents = Class.forName("com.github.retrooper.packetevents.PacketEvents");
            Object api = packetEvents.getMethod("getAPI").invoke(null);
            packetEventManager = api.getClass().getMethod("getEventManager").invoke(api);
            Class<?> listenerInterface = Class.forName("com.github.retrooper.packetevents.event.PacketListener");
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("onPacketSend") && args != null && args.length == 1) handlePacketSend(args[0]);
                return defaultValue(method.getReturnType());
            };
            packetListener = Proxy.newProxyInstance(plugin.getClass().getClassLoader(), new Class<?>[]{listenerInterface}, handler);
            Method register = null;
            for (Method method : packetEventManager.getClass().getMethods()) {
                if (method.getName().equals("registerListener") && method.getParameterCount() >= 1 && method.getParameterTypes()[0].isAssignableFrom(listenerInterface)) {
                    register = method; break;
                }
            }
            if (register == null) throw new NoSuchMethodException("registerListener(PacketListener)");
            Object[] arguments = new Object[register.getParameterCount()];
            arguments[0] = packetListener;
            for (int i = 1; i < arguments.length; i++) arguments[i] = defaultValue(register.getParameterTypes()[i]);
            register.invoke(packetEventManager, arguments);
            status.put("PacketEvents nature replacement", "hooked (runtime signature check required)");
        } catch (Throwable e) {
            status.put("PacketEvents nature replacement", "hook failed: " + e.getClass().getSimpleName());
            plugin.getLogger().warning("Nature sound replacement could not hook PacketEvents: " + e.getMessage());
        }
    }

    private void handlePacketSend(Object event) {
        try {
            Object type = event.getClass().getMethod("getPacketType").invoke(event);
            String typeName = String.valueOf(type).toUpperCase(Locale.ROOT);
            if (!typeName.contains("SOUND")) return;
            String original = readSoundName(event, typeName);
            if (original == null) return;
            ConfigurationSection mappings = configs.replacements().getConfigurationSection("replacements");
            if (mappings == null) return;
            ConfigurationSection replacement = null;
            for (String key : mappings.getKeys(false)) if (key.equalsIgnoreCase(original) || key.equalsIgnoreCase(original.replace("minecraft:", ""))) {
                replacement = mappings.getConfigurationSection(key); break;
            }
            if (replacement == null || !replacement.getBoolean("enabled", false)) return;
            Object user = event.getClass().getMethod("getPlayer").invoke(event);
            if (!(user instanceof Player player)) return;
            String sound = replacement.getString("sound", "").trim();
            if (sound.isBlank()) return;
            double rawVolume = replacement.getDouble("volume", 1.0);
            double rawPitch = replacement.getDouble("pitch", 1.0);
            if (!Double.isFinite(rawVolume) || rawVolume < 0.0 || !Double.isFinite(rawPitch) || rawPitch <= 0.0) return;
            float volume = (float) rawVolume;
            float pitch = (float) rawPitch;
            // Cancellation happens only after the replacement has passed all local validation.
            try { event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true); } catch (Throwable ignored) {}
            Bukkit.getScheduler().runTask(plugin, () -> engine.playDirect(player, sound, volume, pitch));
        } catch (Throwable ignored) {
        }
    }

    private String readSoundName(Object event, String packetTypeName) {
        List<String> wrappers = new ArrayList<>();
        if (packetTypeName.contains("ENTITY")) wrappers.add("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect");
        wrappers.add("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect");
        for (String wrapperName : wrappers) {
            try {
                Class<?> wrapper = Class.forName(wrapperName);
                Constructor<?> constructor = null;
                for (Constructor<?> candidate : wrapper.getConstructors()) {
                    if (candidate.getParameterCount() == 1 && candidate.getParameterTypes()[0].isAssignableFrom(event.getClass())) {
                        constructor = candidate; break;
                    }
                }
                if (constructor == null) continue;
                Object wrapped = constructor.newInstance(event);
                for (String getter : List.of("getSoundEffect", "getSound", "getSoundType")) {
                    try {
                        Object sound = wrapped.getClass().getMethod(getter).invoke(wrapped);
                        String name = deepName(sound);
                        if (name != null) return name;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String deepName(Object value) {
        if (value == null) return null;
        String direct = String.valueOf(value);
        if (direct.contains(":")) return direct.toLowerCase(Locale.ROOT);
        for (String method : List.of("getName", "getKey", "getId", "getValue")) {
            try {
                Object nested = value.getClass().getMethod(method).invoke(value);
                if (nested != null && nested != value) {
                    String name = String.valueOf(nested);
                    if (!name.isBlank()) return name.toLowerCase(Locale.ROOT);
                }
            } catch (Throwable ignored) {
            }
        }
        return direct.toLowerCase(Locale.ROOT);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
