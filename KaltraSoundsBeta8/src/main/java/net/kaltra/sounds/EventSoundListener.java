package net.kaltra.sounds;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class EventSoundListener implements Listener {
    private final JavaPlugin plugin;
    private final ConfigHub configs;
    private final SoundEngine engine;
    private final SituationalModule situational;
    private final RegionModule regions;
    private final DiscModule discs;
    private final SoundListGui gui;
    private final IntegrationModule integrations;

    EventSoundListener(JavaPlugin plugin, ConfigHub configs, SoundEngine engine, SituationalModule situational, RegionModule regions, DiscModule discs, SoundListGui gui, IntegrationModule integrations) {
        this.plugin = plugin;
        this.configs = configs;
        this.engine = engine;
        this.situational = situational;
        this.regions = regions;
        this.discs = discs;
        this.gui = gui;
        this.integrations = integrations;
    }

    private boolean play(String name, Player actor, Location origin, boolean cancelled) {
        ConfigurationSection rule = configs.eventRule(name);
        if (rule == null || !rule.getBoolean("Enabled", false)) return false;
        if (cancelled && rule.getBoolean("Cancellable", false)) return false;
        return engine.playRule(rule, actor, origin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location destination = event.getTo() == null ? player.getLocation() : event.getTo();
        boolean worldChanged = event.getFrom().getWorld() != null && event.getTo() != null
                && event.getTo().getWorld() != null && !event.getFrom().getWorld().equals(event.getTo().getWorld());
        boolean preventTeleport = false;
        if (worldChanged) {
            play("World Change", player, destination, event.isCancelled());
            ConfigurationSection worldRule = configs.eventRule("World Change");
            preventTeleport = worldRule != null && worldRule.getBoolean("Enabled", false)
                    && worldRule.getBoolean("Prevent Teleport Sound", false);
        }
        if (!preventTeleport && event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) {
            play("Teleport", player, destination, event.isCancelled());
        }
        if (!event.isCancelled()) Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                situational.syncPlayer(player, true);
                regions.syncPlayer(player, true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String cause = "CUSTOM";
        if (player.getLastDamageCause() != null) cause = player.getLastDamageCause().getCause().name();
        situational.onDeath(player, cause);
        Player killer = player.getKiller();
        if (killer != null) {
            play("Player Kill", killer, killer.getLocation(), false);
            play("Player Killed", player, player.getLocation(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) { play("Respawn", event.getPlayer(), event.getRespawnLocation(), false); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!integrations.deferJoinSound(player)) {
            play(player.hasPlayedBefore() ? "Join Server" : "First Join", player, player.getLocation(), false);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                situational.syncPlayer(player, false);
                regions.syncPlayer(player, false);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        play("Leave Server", event.getPlayer(), event.getPlayer().getLocation(), false);
        regions.onQuit(event.getPlayer());
        situational.onQuit(event.getPlayer());
        discs.onQuit(event.getPlayer());
        gui.onQuit(event.getPlayer());
        integrations.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onKick(PlayerKickEvent event) { play("Player Kicked", event.getPlayer(), event.getPlayer().getLocation(), event.isCancelled()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBedEnter(PlayerBedEnterEvent event) { play("Bed Enter", event.getPlayer(), event.getBed().getLocation(), event.isCancelled()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        play("Bed Leave", event.getPlayer(), event.getBed().getLocation(), false);
        if (Math.floorMod(event.getPlayer().getWorld().getTime(), 24000L) < 300L) {
            play("Wake Up", event.getPlayer(), event.getBed().getLocation(), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onHeld(PlayerItemHeldEvent event) {
        ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
        situational.onItemHeld(event.getPlayer(), item, event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(PlayerLevelChangeEvent event) { play("Change Level", event.getPlayer(), event.getPlayer().getLocation(), false); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) play("Craft Item", player, player.getLocation(), event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) { play("Drop Item", event.getPlayer(), event.getItemDrop().getLocation(), event.isCancelled()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEditBook(PlayerEditBookEvent event) { play("Edit Book", event.getPlayer(), event.getPlayer().getLocation(), event.isCancelled()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onHit(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        ItemStack held = null;
        if (damager instanceof LivingEntity living && living.getEquipment() != null) held = living.getEquipment().getItemInMainHand();
        situational.onHit(damager, event.getEntity(), held, event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnace(FurnaceExtractEvent event) { play("Furnace Extract", event.getPlayer(), event.getBlock().getLocation(), false); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onGameMode(PlayerGameModeChangeEvent event) { situational.onGameMode(event.getPlayer(), event.getNewGameMode().name(), event.isCancelled()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (gui.handle(event)) return;
        if (event.getWhoClicked() instanceof Player player) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) clicked = event.getCursor();
            situational.onItemClicked(player, clicked, event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        gui.handleClose(event);
        if (event.getPlayer() instanceof Player player) play("Inventory Close", player, player.getLocation(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        if (integrations.handlesGenericChat()) return;
        Player player = event.getPlayer();
        String message = event.getMessage();
        boolean cancelled = event.isCancelled();
        List<Player> recipients = new ArrayList<>(event.getRecipients());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) situational.onChat(player, message, cancelled, recipients);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        situational.onCommand(event.getPlayer(), event.getMessage(), event.isCancelled());
        String command = event.getMessage().toLowerCase(Locale.ROOT);
        if (command.startsWith("/ban ") || command.startsWith("/minecraft:ban ")) play("Player Ban", event.getPlayer(), event.getPlayer().getLocation(), event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        String command = event.getCommand().toLowerCase(Locale.ROOT);
        if (command.startsWith("ban ") || command.startsWith("minecraft:ban ")) {
            Location origin = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getSpawnLocation();
            play("Player Ban", null, origin, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) { situational.onItemSwung(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (regions.handleWand(event)) return;
        discs.handle(event);
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) { discs.handleBreak(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPortal(PortalCreateEvent event) {
        Location origin = event.getBlocks().isEmpty() ? null : event.getBlocks().get(0).getLocation();
        play("Portal Create", null, origin, event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFlight(PlayerToggleFlightEvent event) { play(event.isFlying() ? "Start Flying" : "Stop Flying", event.getPlayer(), event.getPlayer().getLocation(), event.isCancelled()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSwap(PlayerSwapHandItemsEvent event) { play("Swap Hands", event.getPlayer(), event.getPlayer().getLocation(), event.isCancelled()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSneak(PlayerToggleSneakEvent event) { play("Toggle Sneak", event.getPlayer(), event.getPlayer().getLocation(), event.isCancelled()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onWeather(WeatherChangeEvent event) {
        play(event.toWeatherState() ? "Weather Rain" : "Weather Rain End", null, event.getWorld().getSpawnLocation(), event.isCancelled());
    }

    void onDynamicJump(Entity entity, boolean entityEvent) {
        if (entity == null) return;
        Player actor = entity instanceof Player player ? player : null;
        play(entityEvent ? "Entity Jump" : "Player Jump", actor, entity.getLocation(), false);
    }
}
