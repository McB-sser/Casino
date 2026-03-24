package mcbesser.casino;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class HorseRaceManager {

    public static final List<ItemStack> RACER_ITEMS = List.of(
        new ItemStack(Material.IRON_HORSE_ARMOR),
        new ItemStack(Material.GOLDEN_HORSE_ARMOR),
        new ItemStack(Material.COPPER_HORSE_ARMOR),
        new ItemStack(Material.NETHERITE_HORSE_ARMOR)
    );

    private static final String CONFIG_ROOT = "horse-races";

    private final JavaPlugin plugin;
    private final NamespacedKey raceKey;
    private final NamespacedKey racerIndexKey;
    private final NamespacedKey markerKey;
    private final Map<String, HorseRaceInstance> races = new HashMap<>();
    private final Set<String> activeRaces = new HashSet<>();

    public HorseRaceManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.raceKey = new NamespacedKey(plugin, "horse_race");
        this.racerIndexKey = new NamespacedKey(plugin, "horse_race_racer");
        this.markerKey = new NamespacedKey(plugin, "horse_race_marker");
    }

    public void load() {
        races.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(CONFIG_ROOT);
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            String worldId = section.getString(key + ".world");
            if (worldId == null) {
                continue;
            }

            World world = Bukkit.getWorld(UUID.fromString(worldId));
            if (world == null) {
                continue;
            }

            Location location = new Location(
                world,
                section.getInt(key + ".x"),
                section.getInt(key + ".y"),
                section.getInt(key + ".z")
            );
            races.put(serializeKey(location), new HorseRaceInstance(location));
        }
    }

    public void save() {
        plugin.getConfig().set(CONFIG_ROOT, null);
        ConfigurationSection section = plugin.getConfig().createSection(CONFIG_ROOT);

        int index = 0;
        for (HorseRaceInstance instance : races.values()) {
            Location location = instance.lodestoneLocation();
            String path = "race-" + index++;
            section.set(path + ".world", location.getWorld().getUID().toString());
            section.set(path + ".x", location.getBlockX());
            section.set(path + ".y", location.getBlockY());
            section.set(path + ".z", location.getBlockZ());
        }

        plugin.saveConfig();
    }

    public boolean registerRace(Block lodestone) {
        if (lodestone.getType() != Material.LODESTONE) {
            return false;
        }

        String key = serializeKey(lodestone.getLocation());
        if (races.containsKey(key)) {
            return false;
        }

        HorseRaceInstance instance = new HorseRaceInstance(lodestone.getLocation());
        races.put(key, instance);
        spawnRaceDisplays(instance.lodestoneLocation());
        save();
        return true;
    }

    public boolean removeRace(Location location, boolean dropActivationItems) {
        HorseRaceInstance removed = races.remove(serializeKey(location));
        activeRaces.remove(serializeKey(location));
        if (removed == null) {
            return false;
        }

        removeRaceDisplays(removed.lodestoneLocation());
        removeAttachedFrames(removed.lodestoneLocation(), dropActivationItems);
        save();
        return true;
    }

    public boolean isRace(Location location) {
        return races.containsKey(serializeKey(location));
    }

    public boolean beginRace(Location location) {
        return activeRaces.add(serializeKey(location));
    }

    public void endRace(Location location) {
        activeRaces.remove(serializeKey(location));
    }

    public Collection<HorseRaceInstance> getRaces() {
        return Collections.unmodifiableCollection(races.values());
    }

    public List<HorseRaceInstance> getRacesInChunk(World world, int chunkX, int chunkZ) {
        return races.values().stream()
            .filter(instance -> instance.lodestoneLocation().getWorld().equals(world))
            .filter(instance -> instance.lodestoneLocation().getChunk().getX() == chunkX)
            .filter(instance -> instance.lodestoneLocation().getChunk().getZ() == chunkZ)
            .toList();
    }

    public void spawnAllDisplays() {
        for (HorseRaceInstance instance : races.values()) {
            spawnRaceDisplays(instance.lodestoneLocation());
        }
    }

    public void spawnRaceDisplays(Location lodestoneLocation) {
        if (!lodestoneLocation.isChunkLoaded()) {
            return;
        }

        removeRaceDisplays(lodestoneLocation);
        World world = lodestoneLocation.getWorld();
        if (world == null) {
            return;
        }

        for (int i = 0; i < RACER_ITEMS.size(); i++) {
            ItemDisplay racer = (ItemDisplay) world.spawnEntity(getRacerLocation(lodestoneLocation, i, 0.0), EntityType.ITEM_DISPLAY);
            racer.setItemStack(RACER_ITEMS.get(i));
            racer.setBillboard(Display.Billboard.FIXED);
            racer.setGravity(false);
            racer.setPersistent(false);
            racer.setInvulnerable(true);
            racer.setInterpolationDuration(1);
            racer.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                getRacerRotation(0.0),
                new Vector3f(0.35f, 0.35f, 0.35f),
                new Quaternionf()
            ));
            racer.getPersistentDataContainer().set(raceKey, PersistentDataType.STRING, serializeKey(lodestoneLocation));
            racer.getPersistentDataContainer().set(racerIndexKey, PersistentDataType.INTEGER, i);
        }

        spawnTrackMarkers(lodestoneLocation);
    }

    public void removeRaceDisplays(Location lodestoneLocation) {
        World world = lodestoneLocation.getWorld();
        if (world == null) {
            return;
        }

        String key = serializeKey(lodestoneLocation);
        for (Entity entity : world.getNearbyEntities(lodestoneLocation.clone().add(0.5, 0.8, 0.5), 2.0, 1.5, 2.0)) {
            String stored = entity.getPersistentDataContainer().get(raceKey, PersistentDataType.STRING);
            if (key.equals(stored)) {
                entity.remove();
            }
        }
    }

    public @Nullable ItemDisplay getRacerDisplay(Location lodestoneLocation, int index) {
        World world = lodestoneLocation.getWorld();
        if (world == null) {
            return null;
        }

        String key = serializeKey(lodestoneLocation);
        for (Entity entity : world.getNearbyEntities(lodestoneLocation.clone().add(0.5, 0.8, 0.5), 2.0, 1.5, 2.0)) {
            if (!(entity instanceof ItemDisplay itemDisplay)) {
                continue;
            }

            String stored = itemDisplay.getPersistentDataContainer().get(raceKey, PersistentDataType.STRING);
            Integer storedIndex = itemDisplay.getPersistentDataContainer().get(racerIndexKey, PersistentDataType.INTEGER);
            if (key.equals(stored) && storedIndex != null && storedIndex == index) {
                return itemDisplay;
            }
        }
        return null;
    }

    public Location getRacerLocation(Location lodestoneLocation, int racerIndex, double progress) {
        double track = normalizeProgress(progress);
        double inset = 0.0875;
        double size = 1.0 - (inset * 2.0);
        double laneOffset = (racerIndex - 1.5) * 0.035;

        double x;
        double z;

        if (track < 0.25) {
            double t = track / 0.25;
            x = inset + (size * t);
            z = inset + laneOffset;
        } else if (track < 0.50) {
            double t = (track - 0.25) / 0.25;
            x = 1.0 - inset - laneOffset;
            z = inset + (size * t);
        } else if (track < 0.75) {
            double t = (track - 0.50) / 0.25;
            x = 1.0 - inset - (size * t);
            z = 1.0 - inset - laneOffset;
        } else {
            double t = (track - 0.75) / 0.25;
            x = inset + laneOffset;
            z = 1.0 - inset - (size * t);
        }

        return lodestoneLocation.clone().add(x, 1.14, z);
    }

    public Quaternionf getRacerRotation(double progress) {
        double track = normalizeProgress(progress);
        int segment = (int) Math.floor(track * 4.0) % 4;
        float yaw = switch (segment) {
            case 0 -> (float) Math.toRadians(180);
            case 1 -> (float) Math.toRadians(90);
            case 2 -> 0.0f;
            default -> (float) Math.toRadians(270);
        };
        return new Quaternionf().rotateY(yaw);
    }

    private double normalizeProgress(double progress) {
        double normalized = progress % 1.0;
        return normalized < 0 ? normalized + 1.0 : normalized;
    }

    public void shutdown() {
        activeRaces.clear();
        for (HorseRaceInstance instance : races.values()) {
            removeRaceDisplays(instance.lodestoneLocation());
        }
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void removeAttachedFrames(Location lodestoneLocation, boolean dropActivationItems) {
        World world = lodestoneLocation.getWorld();
        if (world == null) {
            return;
        }

        Block block = lodestoneLocation.getBlock();
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            Location frameSearch = block.getRelative(face).getLocation().add(0.5, 0.5, 0.5);
            for (Entity entity : world.getNearbyEntities(frameSearch, 0.6, 0.6, 0.6)) {
                if (!(entity instanceof ItemFrame frame)) {
                    continue;
                }

                if (frame.getAttachedFace() != face.getOppositeFace()) {
                    continue;
                }

                frame.remove();
                if (dropActivationItems) {
                    world.dropItemNaturally(lodestoneLocation.clone().add(0.5, 0.5, 0.5), new ItemStack(Material.ITEM_FRAME));
                    world.dropItemNaturally(lodestoneLocation.clone().add(0.5, 0.5, 0.5), new ItemStack(Material.LEATHER_HORSE_ARMOR));
                }
                return;
            }
        }
    }

    public @Nullable ItemFrame findAttachedFrame(Location lodestoneLocation) {
        World world = lodestoneLocation.getWorld();
        if (world == null) {
            return null;
        }

        Block block = lodestoneLocation.getBlock();
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            Location frameSearch = block.getRelative(face).getLocation().add(0.5, 0.5, 0.5);
            for (Entity entity : world.getNearbyEntities(frameSearch, 0.6, 0.6, 0.6)) {
                if (entity instanceof ItemFrame frame && frame.getAttachedFace() == face.getOppositeFace()) {
                    return frame;
                }
            }
        }
        return null;
    }

    private void spawnTrackMarkers(Location lodestoneLocation) {
        World world = lodestoneLocation.getWorld();
        if (world == null) {
            return;
        }

        double[] xs = {0.1875, 0.2275};
        double[] zs = {0.00125, 0.04125, 0.08125, 0.12125};
        for (int row = 0; row < zs.length; row++) {
            for (int col = 0; col < xs.length; col++) {
                BlockDisplay marker = (BlockDisplay) world.spawnEntity(
                    lodestoneLocation.clone().add(xs[col], 1.01, zs[row]),
                    EntityType.BLOCK_DISPLAY
                );
                boolean white = (row + col) % 2 == 0;
                marker.setBlock(white ? Material.WHITE_CONCRETE.createBlockData() : Material.BLACK_CONCRETE.createBlockData());
                marker.setBillboard(Display.Billboard.FIXED);
                marker.setPersistent(false);
                marker.setInvulnerable(true);
                marker.setInterpolationDuration(1);
                marker.setTransformation(new Transformation(
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    new Quaternionf(),
                    new Vector3f(0.04f, 0.012f, 0.04f),
                    new Quaternionf()
                ));
                marker.getPersistentDataContainer().set(raceKey, PersistentDataType.STRING, serializeKey(lodestoneLocation));
                marker.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            }
        }
    }

    public record HorseRaceInstance(Location lodestoneLocation) {
    }
}
