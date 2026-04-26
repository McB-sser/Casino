package mcbesser.casino;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
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
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CoinFlipManager {

    private static final String CONFIG_ROOT = "coin-flips";
    private static final int COIN_ANIMATION_INTERPOLATION_TICKS = 2;

    private final JavaPlugin plugin;
    private final NamespacedKey gameKey;
    private final NamespacedKey displayTypeKey;
    private final Map<String, CoinFlipInstance> games = new HashMap<>();

    public CoinFlipManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.gameKey = new NamespacedKey(plugin, "coin_flip");
        this.displayTypeKey = new NamespacedKey(plugin, "coin_flip_type");
    }

    public void load() {
        games.clear();
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
                    section.getInt(key + ".z"));
            games.put(serializeKey(location), new CoinFlipInstance(location));
        }
    }

    public void save() {
        plugin.getConfig().set(CONFIG_ROOT, null);
        ConfigurationSection section = plugin.getConfig().createSection(CONFIG_ROOT);

        int index = 0;
        for (CoinFlipInstance instance : games.values()) {
            Location location = instance.jukeboxLocation();
            String path = "coinflip-" + index++;
            section.set(path + ".world", location.getWorld().getUID().toString());
            section.set(path + ".x", location.getBlockX());
            section.set(path + ".y", location.getBlockY());
            section.set(path + ".z", location.getBlockZ());
        }

        plugin.saveConfig();
    }

    public boolean register(Block jukebox) {
        if (jukebox.getType() != Material.JUKEBOX) {
            return false;
        }

        String key = serializeKey(jukebox.getLocation());
        if (games.containsKey(key)) {
            return false;
        }

        CoinFlipInstance instance = new CoinFlipInstance(jukebox.getLocation());
        games.put(key, instance);
        spawnDisplays(instance.jukeboxLocation());
        save();
        return true;
    }

    public boolean remove(Location location, boolean dropItems) {
        CoinFlipInstance removed = games.remove(serializeKey(location));
        if (removed == null) {
            return false;
        }

        removeDisplays(removed.jukeboxLocation());
        removeAttachedFrame(removed.jukeboxLocation(), dropItems);
        save();
        return true;
    }

    public boolean isGame(Location location) {
        return games.containsKey(serializeKey(location));
    }

    public Collection<CoinFlipInstance> getGames() {
        return Collections.unmodifiableCollection(games.values());
    }

    public List<CoinFlipInstance> getGamesInChunk(World world, int chunkX, int chunkZ) {
        return games.values().stream()
                .filter(instance -> instance.jukeboxLocation().getWorld().equals(world))
                .filter(instance -> instance.jukeboxLocation().getChunk().getX() == chunkX)
                .filter(instance -> instance.jukeboxLocation().getChunk().getZ() == chunkZ)
                .toList();
    }

    public void spawnAllDisplays() {
        for (CoinFlipInstance instance : games.values()) {
            spawnDisplays(instance.jukeboxLocation());
        }
    }

    public void spawnDisplays(Location location) {
        if (!CasinoDisplayUtil.shouldLoadDisplay(plugin, location)) {
            return;
        }

        removeDisplays(location);
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay snow = spawnItemDisplay(location, "snowball_static", Material.SNOWBALL,
                location.clone().add(0.49, 1.053, 0.499), new Vector3f(0.28f, 0.28f, 0.28f));
        snow.setTransformation(createCoinFaceTransformation(0.28f, 270.0f));
        ItemDisplay fire = spawnItemDisplay(location, "fire_static", Material.FIRE_CHARGE,
                location.clone().add(0.482, 1.051, 0.499), new Vector3f(0.24f, 0.24f, 0.24f));
        fire.setTransformation(createCoinFaceTransformation(0.24f, 90.0f));
        ItemDisplay coinSnow = spawnItemDisplay(location, "coin_flip_snowball", Material.SNOWBALL,
                location.clone().add(0.5, 1.26, 0.5), new Vector3f(0.34f, 0.34f, 0.34f));
        coinSnow.setTransformation(createCoinFaceTransformation(0.28f, 270.0f));
        coinSnow.setVisibleByDefault(false);
        ItemDisplay coinFire = spawnItemDisplay(location, "coin_flip_fire", Material.FIRE_CHARGE,
                location.clone().add(0.5, 1.252, 0.508), new Vector3f(0.31f, 0.31f, 0.31f));
        coinFire.setTransformation(createCoinFaceTransformation(0.24f, 90.0f));
        coinFire.setVisibleByDefault(false);
        TextDisplay multiplier = spawnTextDisplay(location, "multiplier", location.clone().add(0.5, 1.18, 0.82));
        multiplier.setVisibleByDefault(false);

        double[] xs = { 0.38, 0.50, 0.62 };
        for (int i = 0; i < xs.length; i++) {
            spawnWoolDisplay(location, "timer_" + i, Material.GREEN_WOOL, location.clone().add(xs[i], 1.01, 0.06));
        }
    }

    public void removeDisplays(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        String key = serializeKey(location);
        for (Entity entity : world.getNearbyEntities(location.clone().add(0.5, 1.0, 0.5), 1.5, 1.0, 1.5)) {
            String stored = entity.getPersistentDataContainer().get(gameKey, PersistentDataType.STRING);
            if (key.equals(stored)) {
                entity.remove();
            }
        }
    }

    public void setTimerLights(Location location, int greenCount) {
        for (int i = 0; i < 3; i++) {
            BlockDisplay display = getBlockDisplay(location, "timer_" + i);
            if (display == null || !display.isValid()) {
                continue;
            }
            Material material = i < greenCount ? Material.GREEN_WOOL : Material.RED_WOOL;
            display.setBlock(material.createBlockData());
        }
    }

    public void resetTable(Location location) {
        ItemDisplay snow = getItemDisplay(location, "snowball_static");
        ItemDisplay fire = getItemDisplay(location, "fire_static");
        ItemDisplay coinSnow = getItemDisplay(location, "coin_flip_snowball");
        ItemDisplay coinFire = getItemDisplay(location, "coin_flip_fire");
        if (snow != null) {
            snow.setVisibleByDefault(true);
        }
        if (fire != null) {
            fire.setVisibleByDefault(true);
        }
        if (coinSnow != null) {
            coinSnow.setVisibleByDefault(false);
            coinSnow.teleport(location.clone().add(0.49, 1.053, 0.499));
            coinSnow.setTransformation(createCoinFaceTransformation(0.28f, 270.0f));
        }
        if (coinFire != null) {
            coinFire.setVisibleByDefault(false);
            coinFire.teleport(location.clone().add(0.482, 1.051, 0.499));
            coinFire.setTransformation(createCoinFaceTransformation(0.24f, 90.0f));
        }
        setTimerLights(location, 3);
    }

    public @Nullable ItemDisplay getItemDisplay(Location location, String type) {
        return getDisplay(location, type, ItemDisplay.class);
    }

    public @Nullable BlockDisplay getBlockDisplay(Location location, String type) {
        return getDisplay(location, type, BlockDisplay.class);
    }

    public @Nullable TextDisplay getTextDisplay(Location location, String type) {
        return getDisplay(location, type, TextDisplay.class);
    }

    public void setMultiplierDisplay(Location location, @Nullable Component text) {
        TextDisplay display = getTextDisplay(location, "multiplier");
        if (display == null || !display.isValid()) {
            return;
        }

        if (text == null) {
            display.setVisibleByDefault(false);
            return;
        }

        display.text(text);
        display.setVisibleByDefault(true);
    }

    public @Nullable ItemFrame findAttachedFrame(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        Block block = location.getBlock();
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP,
                BlockFace.DOWN)) {
            Location frameSearch = block.getRelative(face).getLocation().add(0.5, 0.5, 0.5);
            for (Entity entity : world.getNearbyEntities(frameSearch, 0.6, 0.6, 0.6)) {
                if (entity instanceof ItemFrame frame && frame.getAttachedFace() == face.getOppositeFace()) {
                    return frame;
                }
            }
        }
        return null;
    }

    public void shutdown() {
        for (CoinFlipInstance instance : games.values()) {
            removeDisplays(instance.jukeboxLocation());
        }
    }

    public void syncDisplays() {
        for (CoinFlipInstance instance : games.values()) {
            Location location = instance.jukeboxLocation();
            if (!CasinoDisplayUtil.shouldLoadDisplay(plugin, location)) {
                removeDisplays(location);
                continue;
            }
            if (!hasAllDisplays(location)) {
                spawnDisplays(location);
            }
        }
    }

    private boolean hasAllDisplays(Location location) {
        if (getItemDisplay(location, "snowball_static") == null
            || getItemDisplay(location, "fire_static") == null
            || getItemDisplay(location, "coin_flip_snowball") == null
            || getItemDisplay(location, "coin_flip_fire") == null
            || getTextDisplay(location, "multiplier") == null) {
            return false;
        }

        for (int i = 0; i < 3; i++) {
            if (getBlockDisplay(location, "timer_" + i) == null) {
                return false;
            }
        }
        return true;
    }

    private <T extends Entity> @Nullable T getDisplay(Location location, String type, Class<T> expectedType) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        String key = serializeKey(location);
        for (Entity entity : world.getNearbyEntities(location.clone().add(0.5, 1.0, 0.5), 1.5, 1.0, 1.5)) {
            if (!expectedType.isInstance(entity)) {
                continue;
            }

            String stored = entity.getPersistentDataContainer().get(gameKey, PersistentDataType.STRING);
            String storedType = entity.getPersistentDataContainer().get(displayTypeKey, PersistentDataType.STRING);
            if (key.equals(stored) && type.equals(storedType)) {
                return expectedType.cast(entity);
            }
        }
        return null;
    }

    private ItemDisplay spawnItemDisplay(Location gameLocation, String type, Material material, Location spawnLocation,
            Vector3f scale) {
        World world = gameLocation.getWorld();
        ItemDisplay display = (ItemDisplay) world.spawnEntity(spawnLocation, EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(material));
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(COIN_ANIMATION_INTERPOLATION_TICKS);
        display.setTeleportDuration(COIN_ANIMATION_INTERPOLATION_TICKS);
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf(),
                scale,
                new Quaternionf()));
        display.getPersistentDataContainer().set(gameKey, PersistentDataType.STRING, serializeKey(gameLocation));
        display.getPersistentDataContainer().set(displayTypeKey, PersistentDataType.STRING, type);
        return display;
    }

    private void spawnWoolDisplay(Location gameLocation, String type, Material material, Location spawnLocation) {
        World world = gameLocation.getWorld();
        BlockDisplay display = (BlockDisplay) world.spawnEntity(spawnLocation, EntityType.BLOCK_DISPLAY);
        display.setBlock(material.createBlockData());
        display.setBillboard(Display.Billboard.FIXED);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf().rotateY((float) Math.toRadians(90.0f)),
                new Vector3f(0.045f, 0.02f, 0.07f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(gameKey, PersistentDataType.STRING, serializeKey(gameLocation));
        display.getPersistentDataContainer().set(displayTypeKey, PersistentDataType.STRING, type);
    }

    private TextDisplay spawnTextDisplay(Location gameLocation, String type, Location spawnLocation) {
        World world = gameLocation.getWorld();
        TextDisplay display = (TextDisplay) world.spawnEntity(spawnLocation, EntityType.TEXT_DISPLAY);
        display.setBillboard(Display.Billboard.CENTER);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.text(Component.empty());
        display.setShadowed(true);
        display.setSeeThrough(false);
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf(),
                new Vector3f(0.6f, 0.6f, 0.6f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(gameKey, PersistentDataType.STRING, serializeKey(gameLocation));
        display.getPersistentDataContainer().set(displayTypeKey, PersistentDataType.STRING, type);
        return display;
    }

    private Transformation createCoinFaceTransformation(float scaleValue, float yawDegrees) {
        return new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf()
                        .rotateX((float) Math.toRadians(90.0f))
                        .rotateY((float) Math.toRadians(yawDegrees)),
                new Vector3f(scaleValue, scaleValue, scaleValue),
                new Quaternionf());
    }

    private void removeAttachedFrame(Location location, boolean dropItems) {
        ItemFrame frame = findAttachedFrame(location);
        if (frame == null) {
            return;
        }

        frame.remove();
        if (dropItems) {
            World world = location.getWorld();
            world.dropItemNaturally(location.clone().add(0.5, 0.5, 0.5), new ItemStack(Material.ITEM_FRAME));
            world.dropItemNaturally(location.clone().add(0.5, 0.5, 0.5), new ItemStack(Material.SNOWBALL));
        }
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":"
                + location.getBlockZ();
    }

    public record CoinFlipInstance(Location jukeboxLocation) {
    }
}
