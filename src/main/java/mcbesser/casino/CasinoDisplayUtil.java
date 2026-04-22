package mcbesser.casino;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CasinoDisplayUtil {
    private static final Map<String, ViewerCacheEntry> VIEWER_CACHE = new HashMap<>();

    private CasinoDisplayUtil() {
    }

    public static boolean hasNearbyViewer(JavaPlugin plugin, Location location) {
        if (plugin == null || location == null || location.getWorld() == null) {
            return false;
        }
        double maxDistance = plugin.getConfig().getDouble("display.max-view-distance", 64.0D);
        double maxDistanceSquared = maxDistance * maxDistance;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline() || player.isDead() || player.getWorld() != location.getWorld()) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) <= maxDistanceSquared) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldLoadDisplay(JavaPlugin plugin, Location location) {
        if (plugin == null || location == null || location.getWorld() == null || !location.isChunkLoaded()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long checkIntervalMillis = ticksToMillis(plugin.getConfig().getLong("display.proximity-check-interval-ticks", 20L));
        long unloadDelayMillis = ticksToMillis(plugin.getConfig().getLong("display.unload-delay-ticks", 100L));
        String key = cacheKey(location);
        ViewerCacheEntry entry = VIEWER_CACHE.computeIfAbsent(key, ignored -> new ViewerCacheEntry());

        if (now - entry.lastCheckMillis >= checkIntervalMillis) {
            entry.lastCheckMillis = now;
            entry.nearby = hasNearbyViewer(plugin, location);
            if (entry.nearby) {
                entry.lastNearbyMillis = now;
            }
        }

        return entry.nearby || now - entry.lastNearbyMillis <= unloadDelayMillis;
    }

    public static void clearCache() {
        VIEWER_CACHE.clear();
    }

    public static long chunkLoadDisplayDelay(JavaPlugin plugin, Chunk chunk) {
        long baseDelay = plugin.getConfig().getLong("display.chunk-load-display-delay-ticks", 20L);
        long spread = plugin.getConfig().getLong("display.chunk-load-display-spread-ticks", 40L);
        if (spread <= 0L || chunk == null) {
            return Math.max(1L, baseDelay);
        }
        int hash = 31 * chunk.getX() + chunk.getZ();
        return Math.max(1L, baseDelay) + Math.floorMod(hash, (int) Math.min(Integer.MAX_VALUE, spread));
    }

    private static long ticksToMillis(long ticks) {
        return Math.max(1L, ticks) * 50L;
    }

    private static String cacheKey(Location location) {
        UUID worldId = location.getWorld().getUID();
        return worldId + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private static final class ViewerCacheEntry {
        private long lastCheckMillis;
        private long lastNearbyMillis;
        private boolean nearby;
    }
}
