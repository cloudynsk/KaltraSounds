package net.kaltra.sounds;

import org.bukkit.Location;
import org.bukkit.World;

record NativeRegion(String name, String owner, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    boolean contains(Location location) {
        World current = location.getWorld();
        if (current == null || !current.getName().equals(world)) return false;
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    boolean intersects(NativeRegion other) {
        if (other == null || !world.equals(other.world)) return false;
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }
}
