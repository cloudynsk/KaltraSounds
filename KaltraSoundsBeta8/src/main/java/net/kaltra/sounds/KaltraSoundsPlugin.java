package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;

public final class KaltraSoundsPlugin extends JavaPlugin {
    private ConfigHub configs;
    private SoundEngine engine;
    private SituationalModule situational;
    private RegionModule regions;
    private NbsModule nbs;
    private DiscModule discs;
    private SoundListGui gui;
    private IntegrationModule integrations;
    private EventSoundListener eventListener;

    @Override
    public void onEnable() {
        try {
            configs = new ConfigHub(this);
            configs.initialize();
            engine = new SoundEngine(this, configs);
            situational = new SituationalModule(this, configs, engine);
            regions = new RegionModule(this, configs, engine);
            nbs = new NbsModule(this, configs, engine);
            discs = new DiscModule(this, configs, engine, nbs);
            gui = new SoundListGui(this, configs, engine);
            integrations = new IntegrationModule(this, configs, engine, situational);
            eventListener = new EventSoundListener(this, configs, engine, situational, regions, discs, gui, integrations);

            Bukkit.getPluginManager().registerEvents(eventListener, this);
            registerJumpEvents();
            situational.start();
            regions.load();
            integrations.initialize();

            PluginCommand command = getCommand("kaltrasounds");
            if (command == null) throw new IllegalStateException("Command kaltrasounds is missing from plugin.yml");
            PmsCommand executor = new PmsCommand(this, configs, engine, regions, discs, nbs, gui, integrations);
            command.setExecutor(executor);
            command.setTabCompleter(executor);

            List<String> problems = configs.validate();
            if (!problems.isEmpty() && configs.config().getBoolean("logging.validation-errors", true)) {
                getLogger().warning("Configuration validation found " + problems.size() + " problem(s). Run /pms validate for exact paths.");
            }
            if (configs.config().getBoolean("logging.startup-summary", true)) {
                getLogger().info("KaltraSounds enabled: event sounds, situational filters, regions, discs, NBS and integrated hooks loaded.");
                getLogger().info("Resource-pack management is intentionally absent. Custom namespaced sounds remain supported.");
            }
        } catch (Throwable e) {
            getLogger().log(Level.SEVERE, "KaltraSounds failed to enable", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (situational != null) situational.stop();
        if (regions != null) regions.stop();
        if (discs != null) discs.stopAll();
        if (nbs != null) nbs.stopAll();
        if (integrations != null) integrations.stop();
        if (engine != null) engine.clearPending();
        getLogger().info("KaltraSounds disabled; scheduled sounds, region loops and integrations were stopped.");
    }

    void reloadEverything() throws IOException {
        if (integrations != null) integrations.stop();
        if (engine != null) engine.clearPending();
        configs.reload();
        situational.start();
        regions.load();
        integrations.initialize();
    }


    void reloadScope(String scope) throws IOException {
        switch (scope.toLowerCase(java.util.Locale.ROOT)) {
            case "all" -> reloadEverything();
            case "sounds" -> {
                engine.clearPending();
                configs.reloadSounds();
                situational.start();
                regions.load();
            }
            case "regions" -> {
                configs.reloadRegions();
                regions.load();
            }
            case "integrations" -> {
                integrations.stop();
                configs.reloadIntegrations();
                integrations.initialize();
            }
            case "messages" -> configs.reloadMessages();
            default -> throw new IllegalArgumentException("Unknown reload scope: " + scope);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerJumpEvents() {
        boolean playerHooked = registerDynamicJump(List.of(
                "com.destroystokyo.paper.event.player.PlayerJumpEvent",
                "io.papermc.paper.event.player.PlayerJumpEvent"
        ), false);
        registerDynamicJump(List.of(
                "com.destroystokyo.paper.event.entity.EntityJumpEvent",
                "io.papermc.paper.event.entity.EntityJumpEvent"
        ), true);
        if (!playerHooked) getLogger().info("No Paper PlayerJumpEvent class was found; Player Jump sounds remain disabled until a compatible event is available.");
    }

    @SuppressWarnings("unchecked")
    private boolean registerDynamicJump(List<String> classNames, boolean entityEvent) {
        for (String className : classNames) {
            try {
                Class<?> raw = Class.forName(className);
                if (!Event.class.isAssignableFrom(raw)) continue;
                Listener marker = new Listener() {};
                EventExecutor executor = (listener, event) -> {
                    Object source = invokeNoArgs(event, "getPlayer", "getEntity");
                    Entity entity = source instanceof Entity value ? value : null;
                    if (entity != null) eventListener.onDynamicJump(entity, entityEvent);
                };
                Bukkit.getPluginManager().registerEvent((Class<? extends Event>) raw, marker, EventPriority.MONITOR, executor, this, true);
                getLogger().info("Hooked jump event " + className);
                return true;
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable e) {
                getLogger().warning("Could not hook " + className + ": " + e.getMessage());
            }
        }
        return false;
    }

    private static Object invokeNoArgs(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                return method.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
