package mcbesser.casino;

import java.util.ArrayList;
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
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ChiseledBookshelfInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class MemoryManager {

    private static final String CONFIG_ROOT = "memory-boards";

    private final JavaPlugin plugin;
    private final NamespacedKey boardKey;
    private final NamespacedKey slotKey;
    private final NamespacedKey displayTypeKey;
    private final Map<String, MemoryBoard> boards = new HashMap<>();

    public MemoryManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.boardKey = new NamespacedKey(plugin, "memory_board");
        this.slotKey = new NamespacedKey(plugin, "memory_slot");
        this.displayTypeKey = new NamespacedKey(plugin, "memory_type");
    }

    public void load() {
        boards.clear();
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

            Location center = new Location(
                    world,
                    section.getInt(key + ".x"),
                    section.getInt(key + ".y"),
                    section.getInt(key + ".z"));
            boards.put(serializeKey(center), new MemoryBoard(center));
        }
    }

    public void save() {
        plugin.getConfig().set(CONFIG_ROOT, null);
        ConfigurationSection section = plugin.getConfig().createSection(CONFIG_ROOT);

        int index = 0;
        for (MemoryBoard board : boards.values()) {
            String path = "memory-" + index++;
            Location center = board.centerLocation();
            section.set(path + ".world", center.getWorld().getUID().toString());
            section.set(path + ".x", center.getBlockX());
            section.set(path + ".y", center.getBlockY());
            section.set(path + ".z", center.getBlockZ());
        }

        plugin.saveConfig();
    }

    public boolean register(Block centerBlock, ItemFrame frame) {
        if (!isValidBoard(centerBlock)) {
            return false;
        }

        String key = serializeKey(centerBlock.getLocation());
        if (boards.containsKey(key)) {
            return false;
        }

        frame.remove();

        MemoryBoard board = new MemoryBoard(centerBlock.getLocation());
        boards.put(key, board);
        spawnDisplays(board.centerLocation());
        save();
        return true;
    }

    public boolean remove(Location center, boolean dropActivator) {
        MemoryBoard removed = boards.remove(serializeKey(center));
        if (removed == null) {
            return false;
        }

        removeDisplays(center);
        removeAttachedFrame(center, dropActivator);
        save();
        return true;
    }

    public boolean isBoard(Location center) {
        return boards.containsKey(serializeKey(center));
    }

    public @Nullable MemoryBoard getBoard(Location center) {
        return boards.get(serializeKey(center));
    }

    public Collection<MemoryBoard> getBoards() {
        return Collections.unmodifiableCollection(boards.values());
    }

    public List<MemoryBoard> getBoardsInChunk(World world, int chunkX, int chunkZ) {
        return boards.values().stream()
                .filter(board -> board.centerLocation().getWorld().equals(world))
                .filter(board -> board.centerLocation().getChunk().getX() == chunkX)
                .filter(board -> board.centerLocation().getChunk().getZ() == chunkZ)
                .toList();
    }

    public @Nullable MemoryBoard findBoardContainingBlock(Block block) {
        for (MemoryBoard board : boards.values()) {
            for (Block shelf : getShelfBlocks(board.centerLocation())) {
                if (sameBlock(shelf.getLocation(), block.getLocation())) {
                    return board;
                }
            }
        }
        return null;
    }

    public void spawnAllDisplays() {
        for (MemoryBoard board : boards.values()) {
            spawnDisplays(board.centerLocation());
        }
    }

    public void spawnDisplays(Location center) {
        if (!center.isChunkLoaded() || !CasinoDisplayUtil.hasNearbyViewer(plugin, center)) {
            return;
        }

        removeDisplays(center);
        spawnStatusDisplay(center);
        for (int slot = 0; slot < 54; slot++) {
            spawnSlotDisplay(center, slot, Material.EGG);
        }
        resetAllSlots(center);
    }

    public void removeDisplays(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        String key = serializeKey(center);
        for (Entity entity : world.getNearbyEntities(center.clone().add(0.5, 1.0, 0.5), 3.5, 2.5, 3.5)) {
            String storedBoard = entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            if (key.equals(storedBoard)) {
                entity.remove();
            }
        }
    }

    public void setSlotItem(Location center, int slot, Material material) {
        Block shelfBlock = getShelfBlock(center, slot / 6);
        if (shelfBlock.getType() != Material.CHISELED_BOOKSHELF || !(shelfBlock.getState() instanceof ChiseledBookshelf shelf)) {
            return;
        }

        int localSlot = slot % 6;
        ChiseledBookshelfInventory inventory = shelf.getInventory();
        inventory.setItem(localSlot, new ItemStack(material));
        shelf.update(true, true);

        ItemDisplay display = getSlotDisplay(center, slot);
        if (display != null && display.isValid()) {
            display.setItemStack(new ItemStack(material));
        }
    }

    public void resetAllSlots(Location center) {
        for (int slot = 0; slot < 54; slot++) {
            setSlotItem(center, slot, Material.EGG);
        }
    }

    public @Nullable Integer getSlotIndex(Entity entity) {
        if (!(entity instanceof ItemDisplay)) {
            return null;
        }
        return entity.getPersistentDataContainer().get(slotKey, PersistentDataType.INTEGER);
    }

    public boolean isSlotDisplay(Entity entity) {
        if (!(entity instanceof ItemDisplay)) {
            return false;
        }
        return entity.getPersistentDataContainer().has(slotKey, PersistentDataType.INTEGER);
    }

    public @Nullable MemoryBoard getBoardForDisplay(Entity entity) {
        String stored = entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
        if (stored == null) {
            return null;
        }
        return boards.get(stored);
    }

    public void setStatusText(Location center, String text) {
        TextDisplay display = getStatusDisplay(center);
        if (display == null || !display.isValid()) {
            return;
        }
        display.text(Component.text(text));
    }

    public @Nullable ItemFrame findAttachedFrame(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        Block block = center.getBlock();
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Location frameSearch = block.getRelative(face).getLocation().add(0.5, 0.5, 0.5);
            for (Entity entity : world.getNearbyEntities(frameSearch, 0.7, 0.7, 0.7)) {
                if (entity instanceof ItemFrame frame && frame.getAttachedFace() == face.getOppositeFace()) {
                    return frame;
                }
            }
        }
        return null;
    }

    public boolean isValidBoard(Block centerBlock) {
        if (centerBlock.getType() != Material.CHISELED_BOOKSHELF) {
            return false;
        }
        if (!(centerBlock.getBlockData() instanceof Directional directional)) {
            return false;
        }

        BlockFace front = directional.getFacing();
        for (Block block : getShelfBlocks(centerBlock.getLocation(), front)) {
            if (block.getType() != Material.CHISELED_BOOKSHELF) {
                return false;
            }
            if (!(block.getBlockData() instanceof Directional other) || other.getFacing() != front) {
                return false;
            }
        }
        return true;
    }

    public void shutdown() {
        for (MemoryBoard board : boards.values()) {
            removeDisplays(board.centerLocation());
        }
    }

    public void syncDisplays() {
        for (MemoryBoard board : boards.values()) {
            Location center = board.centerLocation();
            if (!CasinoDisplayUtil.hasNearbyViewer(plugin, center)) {
                removeDisplays(center);
                continue;
            }
            if (getStatusDisplay(center) == null) {
                spawnDisplays(center);
            }
        }
    }

    private void spawnStatusDisplay(Location center) {
        World world = center.getWorld();
        Location displayLocation = getStatusDisplayLocation(center);
        TextDisplay display = (TextDisplay) world.spawnEntity(displayLocation, EntityType.TEXT_DISPLAY);
        display.setBillboard(Display.Billboard.CENTER);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.text(net.kyori.adventure.text.Component.text(""));
        display.setShadowed(true);
        display.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, serializeKey(center));
        display.getPersistentDataContainer().set(displayTypeKey, PersistentDataType.STRING, "status");
    }

    private void spawnSlotDisplay(Location center, int slot, Material material) {
        World world = center.getWorld();
        Location displayLocation = getSlotDisplayLocation(center, slot);
        ItemDisplay display = (ItemDisplay) world.spawnEntity(displayLocation, EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(material));
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                getDisplayRotation(center),
                new Vector3f(0.28f, 0.28f, 0.28f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, serializeKey(center));
        display.getPersistentDataContainer().set(slotKey, PersistentDataType.INTEGER, slot);
        display.getPersistentDataContainer().set(displayTypeKey, PersistentDataType.STRING, "slot");
    }

    private @Nullable ItemDisplay getSlotDisplay(Location center, int slot) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        String key = serializeKey(center);
        for (Entity entity : world.getNearbyEntities(center.clone().add(0.5, 1.0, 0.5), 4.0, 3.0, 4.0)) {
            if (!(entity instanceof ItemDisplay display)) {
                continue;
            }
            String storedBoard = entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            Integer storedSlot = entity.getPersistentDataContainer().get(slotKey, PersistentDataType.INTEGER);
            String storedType = entity.getPersistentDataContainer().get(displayTypeKey, PersistentDataType.STRING);
            if (key.equals(storedBoard) && storedSlot != null && storedSlot == slot && "slot".equals(storedType)) {
                return display;
            }
        }
        return null;
    }

    private @Nullable TextDisplay getStatusDisplay(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }

        String key = serializeKey(center);
        for (Entity entity : world.getNearbyEntities(center.clone().add(0.5, 1.0, 0.5), 3.5, 2.5, 3.5)) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            String storedBoard = entity.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING);
            String storedType = entity.getPersistentDataContainer().get(displayTypeKey, PersistentDataType.STRING);
            if (key.equals(storedBoard) && "status".equals(storedType)) {
                return display;
            }
        }
        return null;
    }

    private List<Block> getShelfBlocks(Location center) {
        Block block = center.getBlock();
        if (!(block.getBlockData() instanceof Directional directional)) {
            return Collections.emptyList();
        }
        return getShelfBlocks(center, directional.getFacing());
    }

    private List<Block> getShelfBlocks(Location center, BlockFace front) {
        Block centerBlock = center.getBlock();
        BlockFace right = rotateRight(front);
        List<Block> blocks = new ArrayList<>(9);
        for (int row = 1; row >= -1; row--) {
            for (int col = -1; col <= 1; col++) {
                Block block = centerBlock
                        .getRelative(BlockFace.UP, row)
                        .getRelative(right, col);
                blocks.add(block);
            }
        }
        return blocks;
    }

    private Location getStatusDisplayLocation(Location center) {
        Block block = center.getBlock();
        Directional directional = (Directional) block.getBlockData();
        BlockFace front = directional.getFacing();
        Location location = center.clone().add(0.5, -0.72, 0.5);
        addFaceOffset(location, front, 1.18);
        return location;
    }

    private Location getSlotDisplayLocation(Location center, int slot) {
        Block block = center.getBlock();
        Directional directional = (Directional) block.getBlockData();
        BlockFace front = directional.getFacing();
        BlockFace right = rotateRight(front);

        int shelfIndex = slot / 6;
        int localIndex = slot % 6;
        int shelfRow = 1 - (shelfIndex / 3);
        int shelfCol = (shelfIndex % 3) - 1;
        int slotRow = localIndex / 3;
        int slotCol = localIndex % 3;

        double[] slotX = { 0.265, 0.0, -0.265 };
        double[] slotY = { 0.25, -0.25 };

        Location location = center.clone().add(0.5, 0.5, 0.5);
        addFaceOffset(location, right, shelfCol + slotX[slotCol]);
        location.add(0.0, shelfRow + slotY[slotRow], 0.0);
        addFaceOffset(location, front, 0.54);
        return location;
    }

    private Quaternionf getDisplayRotation(Location center) {
        Block block = center.getBlock();
        Directional directional = (Directional) block.getBlockData();
        float yaw = switch (directional.getFacing()) {
            case NORTH -> 180.0f;
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case EAST -> -90.0f;
            default -> 0.0f;
        };

        return new Quaternionf().rotateY((float) Math.toRadians(yaw));
    }

    public int getGlobalSlot(Location center, Block shelfBlock, int localSlot) {
        List<Block> shelves = getShelfBlocks(center);
        for (int shelfIndex = 0; shelfIndex < shelves.size(); shelfIndex++) {
            Block current = shelves.get(shelfIndex);
            if (sameBlock(current.getLocation(), shelfBlock.getLocation())) {
                return (shelfIndex * 6) + localSlot;
            }
        }
        return -1;
    }

    public Block getShelfBlock(Location center, int shelfIndex) {
        List<Block> shelves = getShelfBlocks(center);
        if (shelfIndex < 0 || shelfIndex >= shelves.size()) {
            return center.getBlock();
        }
        return shelves.get(shelfIndex);
    }

    private void removeAttachedFrame(Location center, boolean dropActivator) {
        ItemFrame frame = findAttachedFrame(center);
        if (frame == null) {
            return;
        }

        frame.remove();
        if (!dropActivator) {
            return;
        }

        World world = center.getWorld();
        if (world == null) {
            return;
        }

        Location drop = center.clone().add(0.5, 0.5, 0.5);
        world.dropItemNaturally(drop, new ItemStack(Material.ITEM_FRAME));
        world.dropItemNaturally(drop, new ItemStack(Material.EGG));
    }

    private void addFaceOffset(Location location, BlockFace face, double amount) {
        location.add(face.getModX() * amount, face.getModY() * amount, face.getModZ() * amount);
    }

    private BlockFace rotateRight(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":"
                + location.getBlockZ();
    }

    public record MemoryBoard(Location centerLocation) {
    }
}

