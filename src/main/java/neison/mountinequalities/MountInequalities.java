package neison.mountinequalities;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;

import java.util.*;

public final class MountInequalities extends JavaPlugin implements Listener, CommandExecutor {

    public ZoneManager zoneManager;
    public final Map<UUID, Long> zoneEntryTime = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        zoneManager = new ZoneManager(this);
        PluginCommand cmd = getCommand("mountain");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            getLogger().warning("Команда 'mountain' не найдена. Проверьте plugin.yml");
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.isOp()) continue;
                    Location loc = player.getLocation();
                    Zone zone = zoneManager.getZoneContaining(loc);
                    UUID uuid = player.getUniqueId();

                    if (zone != null) {
                        zoneEntryTime.putIfAbsent(uuid, System.currentTimeMillis());

                        long enteredAt = zoneEntryTime.get(uuid);
                        if (System.currentTimeMillis() - enteredAt >= 60_000) {
                            zoneEntryTime.remove(uuid);
                            Location respawn = player.getBedSpawnLocation();
                            if (respawn == null) respawn = player.getWorld().getSpawnLocation();
                            player.teleport(respawn);
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_TELEPORT, 0.2f, 1);
                        }
                    } else {
                        zoneEntryTime.remove(uuid);
                    }
                }
            }
        }.runTaskTimer(this, 1201L, 1201L);

    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return false;
        if (args.length != 6) {
            player.sendMessage(Component.text("Используйте: /mountain <x1> <y1> <z1> <x2> <y2> <z2>").color(NamedTextColor.YELLOW));
            return true;
        }

        try {
            Location loc1 = new Location(player.getWorld(),
                    Double.parseDouble(args[0]),
                    Double.parseDouble(args[1]),
                    Double.parseDouble(args[2]));

            Location loc2 = new Location(player.getWorld(),
                    Double.parseDouble(args[3]),
                    Double.parseDouble(args[4]),
                    Double.parseDouble(args[5]));

            if (!loc1.getWorld().getName().equals("world")) {
                player.sendMessage(Component.text("Зоны можно создавать только в верхнем мире").color(NamedTextColor.RED));
                return true;
            }

            zoneManager.addZone(loc1, loc2);
            player.sendMessage(Component.text("Зона успешно создана").color(NamedTextColor.GREEN));
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Координаты должны быть числами").color(NamedTextColor.RED));
        }

        return true;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        Location to = event.getTo();
        if (!to.getWorld().getName().equals("world")) return;

        Zone zone = zoneManager.getZoneContaining(to);
        if (zone == null) return;

        player.leaveVehicle();

        Vector knockback = to.toVector().subtract(zone.getCenter().toVector()).normalize().setY(0.5).multiply(1.5);
        player.setVelocity(knockback);
        player.sendActionBar(Component.text("Недостаточно поинтов для доступа").color(NamedTextColor.RED));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 0.1f, 1);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) return;

        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || from == null || !to.getWorld().getName().equals("world")) return;

        if (zoneManager.isInZone(to)) {
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
                event.setCancelled(true);
                player.teleport(from);
            } else if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!event.getPlayer().isOp() && zoneManager.isInZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().isOp() && zoneManager.isInZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getPlayer().isOp() && event.getClickedBlock() != null &&
                zoneManager.isInZone(event.getClickedBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    public static class ZoneManager {
        private final List<Zone> zones = new ArrayList<>();
        private final JavaPlugin plugin;

        public ZoneManager(JavaPlugin plugin) {
            this.plugin = plugin;
            loadZones();
        }

        public void addZone(Location loc1, Location loc2) {
            zones.add(new Zone(loc1, loc2));
            saveZones();
        }

        public boolean isInZone(Location loc) {
            if (!loc.getWorld().getName().equals("world")) return false;
            return zones.stream().anyMatch(zone -> zone.contains(loc));
        }

        public Zone getZoneContaining(Location loc) {
            if (!loc.getWorld().getName().equals("world")) return null;
            return zones.stream().filter(zone -> zone.contains(loc)).findFirst().orElse(null);
        }

        public void saveZones() {
            List<Map<String, Object>> zoneList = new ArrayList<>();
            for (Zone zone : zones) {
                Map<String, Object> data = new HashMap<>();
                data.put("x1", zone.min.getX());
                data.put("y1", zone.min.getY());
                data.put("z1", zone.min.getZ());
                data.put("x2", zone.max.getX());
                data.put("y2", zone.max.getY());
                data.put("z2", zone.max.getZ());
                zoneList.add(data);
            }
            plugin.getConfig().set("zones", zoneList);
            plugin.saveConfig();
        }

        public void loadZones() {
            zones.clear();
            List<Map<?, ?>> zoneList = plugin.getConfig().getMapList("zones");
            World world = Bukkit.getWorld("world");
            if (world == null) return;

            for (Map<?, ?> data : zoneList) {
                Location loc1 = new Location(world,
                        ((Number) data.get("x1")).doubleValue(),
                        ((Number) data.get("y1")).doubleValue(),
                        ((Number) data.get("z1")).doubleValue());

                Location loc2 = new Location(world,
                        ((Number) data.get("x2")).doubleValue(),
                        ((Number) data.get("y2")).doubleValue(),
                        ((Number) data.get("z2")).doubleValue());

                zones.add(new Zone(loc1, loc2));
            }
        }
    }

    public static class Zone {
        private final Location min;
        private final Location max;

        public Zone(Location loc1, Location loc2) {
            min = getMin(loc1, loc2);
            max = getMax(loc1, loc2);
        }

        public boolean contains(Location loc) {
            return loc.getX() >= min.getX() && loc.getX() <= max.getX()
                    && loc.getY() >= min.getY() && loc.getY() <= max.getY()
                    && loc.getZ() >= min.getZ() && loc.getZ() <= max.getZ();
        }

        public Location getCenter() {
            return new Location(min.getWorld(),
                    (min.getX() + max.getX()) / 2,
                    (min.getY() + max.getY()) / 2,
                    (min.getZ() + max.getZ()) / 2);
        }

        private Location getMin(Location a, Location b) {
            return new Location(a.getWorld(),
                    Math.min(a.getX(), b.getX()),
                    Math.min(a.getY(), b.getY()),
                    Math.min(a.getZ(), b.getZ()));
        }

        private Location getMax(Location a, Location b) {
            return new Location(a.getWorld(),
                    Math.max(a.getX(), b.getX()),
                    Math.max(a.getY(), b.getY()),
                    Math.max(a.getZ(), b.getZ()));
        }
    }
}
