package mcbesser.casino;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.bukkit.block.Shelf;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShelfInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class SlotMachineManager {

    private static final String CONFIG_ROOT = "slot-machines";

    private final JavaPlugin plugin;
    private final NamespacedKey handleKey;
    private final Map<String, SlotMachineInstance> machines = new HashMap<>();
    private final Set<String> activeSpins = new HashSet<>();

    public SlotMachineManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.handleKey = new NamespacedKey(plugin, "slot_machine_handle");
    }

    public void load() {
        machines.clear();

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
                plugin.getLogger().warning("Skipping slot machine in missing world: " + worldId);
                continue;
            }

            int x = section.getInt(key + ".x");
            int y = section.getInt(key + ".y");
            int z = section.getInt(key + ".z");
            Location lecternLocation = new Location(world, x, y, z);
            machines.put(serializeKey(lecternLocation), new SlotMachineInstance(lecternLocation));
        }
    }

    public void save() {
        plugin.getConfig().set(CONFIG_ROOT, null);
        ConfigurationSection section = plugin.getConfig().createSection(CONFIG_ROOT);

        int index = 0;
        for (SlotMachineInstance instance : machines.values()) {
            Location location = instance.lecternLocation();
            String path = "machine-" + index++;
            section.set(path + ".world", location.getWorld().getUID().toString());
            section.set(path + ".x", location.getBlockX());
            section.set(path + ".y", location.getBlockY());
            section.set(path + ".z", location.getBlockZ());
        }

        plugin.saveConfig();
    }

    public Material getShelfMaterial() {
        return Material.OAK_SHELF;
    }

    public boolean isValidStructure(Block lecternBlock) {
        return lecternBlock.getType() == Material.LECTERN
            && lecternBlock.getRelative(BlockFace.UP).getType() == Material.OAK_SHELF;
    }

    public boolean isMachine(Location location) {
        return machines.containsKey(serializeKey(location));
    }

    public boolean registerMachine(Block lecternBlock) {
        if (!isValidStructure(lecternBlock)) {
            return false;
        }

        String key = serializeKey(lecternBlock.getLocation());
        if (machines.containsKey(key)) {
            return false;
        }

        SlotMachineInstance instance = new SlotMachineInstance(lecternBlock.getLocation());
        machines.put(key, instance);
        initializeShelf(instance);
        spawnHandle(instance);
        save();
        return true;
    }

    public boolean removeMachine(Location lecternLocation, boolean dropHandle) {
        String key = serializeKey(lecternLocation);
        SlotMachineInstance removed = machines.remove(key);
        activeSpins.remove(key);
        if (removed == null) {
            return false;
        }

        clearShelf(removed);
        removeHandleEntities(removed.lecternLocation());
        if (dropHandle) {
            removed.lecternLocation().getWorld().dropItemNaturally(
                removed.lecternLocation().clone().add(0.5, 1.0, 0.5),
                new ItemStack(Material.LIGHTNING_ROD)
            );
        }
        save();
        return true;
    }

    public Collection<SlotMachineInstance> getMachines() {
        return Collections.unmodifiableCollection(machines.values());
    }

    public boolean beginSpin(Location location) {
        return activeSpins.add(serializeKey(location));
    }

    public void endSpin(Location location) {
        activeSpins.remove(serializeKey(location));
    }

    public void shutdown() {
        activeSpins.clear();
        for (SlotMachineInstance instance : machines.values()) {
            removeHandleEntities(instance.lecternLocation());
        }
    }

    public void spawnAllHandles() {
        for (SlotMachineInstance instance : machines.values()) {
            spawnHandle(instance);
        }
    }

    public @Nullable Shelf getShelfState(Location lecternLocation) {
        Block shelfBlock = lecternLocation.getBlock().getRelative(BlockFace.UP);
        if (shelfBlock.getType() != Material.OAK_SHELF || !(shelfBlock.getState() instanceof Shelf shelf)) {
            return null;
        }
        return shelf;
    }

    public @Nullable ShelfInventory getShelfInventory(Location lecternLocation) {
        Shelf shelf = getShelfState(lecternLocation);
        return shelf == null ? null : shelf.getInventory();
    }

    public BlockFace getFacing(Block lecternBlock) {
        if (lecternBlock.getBlockData() instanceof Directional directional) {
            return directional.getFacing();
        }
        return BlockFace.NORTH;
    }

    public BlockFace getFrontFace(Block lecternBlock) {
        Block shelfBlock = lecternBlock.getRelative(BlockFace.UP);
        if (shelfBlock.getBlockData() instanceof Directional directional) {
            return directional.getFacing();
        }
        return getFacing(lecternBlock).getOppositeFace();
    }

    public Vector3f getForward(BlockFace face) {
        return new Vector3f(face.getModX(), face.getModY(), face.getModZ());
    }

    public Vector3f getRight(BlockFace facing) {
        return switch (facing) {
            case NORTH -> new Vector3f(1, 0, 0);
            case SOUTH -> new Vector3f(-1, 0, 0);
            case EAST -> new Vector3f(0, 0, 1);
            case WEST -> new Vector3f(0, 0, -1);
            default -> new Vector3f(1, 0, 0);
        };
    }

    public Location getHandleLocation(Location lecternLocation) {
        Block lecternBlock = lecternLocation.getBlock();
        BlockFace front = getFrontFace(lecternBlock);
        Vector3f forward = getForward(front);
        Vector3f right = getRight(front);

        return lecternLocation.clone()
            .add(0.5, 1.08, 0.5)
            .add(forward.x() * 0.22, 0.0, forward.z() * 0.22)
            .add(right.x() * -0.49875, 0.0, right.z() * -0.49875);
    }

    public @Nullable ItemDisplay findHandle(Location lecternLocation) {
        World world = lecternLocation.getWorld();
        if (world == null) {
            return null;
        }

        String key = serializeKey(lecternLocation);
        for (Entity entity : world.getNearbyEntities(getHandleLocation(lecternLocation), 1.0, 1.0, 1.0)) {
            if (entity instanceof ItemDisplay itemDisplay) {
                String storedKey = itemDisplay.getPersistentDataContainer().get(handleKey, PersistentDataType.STRING);
                if (key.equals(storedKey)) {
                    return itemDisplay;
                }
            }
        }
        return null;
    }

    public Transformation createHandleTransformation(BlockFace front, boolean pulled) {
        float tiltAngle = pulled ? (float) Math.toRadians(-55) : (float) Math.toRadians(-10);
        float yRotation = switch (front) {
            case NORTH -> 0.0f;
            case SOUTH -> (float) Math.toRadians(180);
            case EAST -> (float) Math.toRadians(-90);
            case WEST -> (float) Math.toRadians(90);
            default -> 0.0f;
        };
        Quaternionf facingRotation = new Quaternionf().rotateY(yRotation);
        Quaternionf tiltRotation = new Quaternionf().rotateX(tiltAngle);
        return new Transformation(
            new Vector3f(0.0f, pulled ? -0.12f : 0.0f, 0.0f),
            facingRotation,
            new Vector3f(0.9f, 0.9f, 0.9f),
            tiltRotation
        );
    }

    public void setShelfContents(Location lecternLocation, ItemStack[] contents) {
        ShelfInventory inventory = getShelfInventory(lecternLocation);
        if (inventory == null) {
            return;
        }

        for (int i = 0; i < inventory.getSize() && i < contents.length; i++) {
            inventory.setItem(i, contents[i]);
        }
    }

    public ItemStack[] getShelfContents(Location lecternLocation) {
        ShelfInventory inventory = getShelfInventory(lecternLocation);
        return inventory == null ? new ItemStack[0] : inventory.getContents();
    }

    private void initializeShelf(SlotMachineInstance instance) {
        setShelfContents(instance.lecternLocation(), new ItemStack[] {
            new ItemStack(Material.DIAMOND),
            new ItemStack(Material.EMERALD),
            new ItemStack(Material.GOLD_INGOT)
        });
    }

    private void clearShelf(SlotMachineInstance instance) {
        ShelfInventory inventory = getShelfInventory(instance.lecternLocation());
        if (inventory != null) {
            inventory.clear();
        }
    }

    private void spawnHandle(SlotMachineInstance instance) {
        Location lecternLocation = instance.lecternLocation();
        removeHandleEntities(lecternLocation);

        World world = lecternLocation.getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay handle = (ItemDisplay) world.spawnEntity(getHandleLocation(lecternLocation), EntityType.ITEM_DISPLAY);
        handle.setItemStack(new ItemStack(Material.LIGHTNING_ROD));
        handle.setBillboard(Display.Billboard.FIXED);
        handle.setGravity(false);
        handle.setPersistent(false);
        handle.setInvulnerable(true);
        handle.setTransformation(createHandleTransformation(getFrontFace(lecternLocation.getBlock()), false));
        handle.getPersistentDataContainer().set(handleKey, PersistentDataType.STRING, serializeKey(lecternLocation));
    }

    private void removeHandleEntities(Location lecternLocation) {
        ItemDisplay handle = findHandle(lecternLocation);
        if (handle != null) {
            handle.remove();
        }
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    public record SlotMachineInstance(Location lecternLocation) {
    }
}
