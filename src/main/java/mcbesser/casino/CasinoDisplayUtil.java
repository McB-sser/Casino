package mcbesser.casino;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CasinoDisplayUtil {
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
}
