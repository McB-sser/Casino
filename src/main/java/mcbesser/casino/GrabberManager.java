package mcbesser.casino;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

public final class GrabberManager {

    private static final String CONFIG_ROOT = "grabber-machines";
    public static final int PRIZE_DISPLAY_COUNT = 29;

    private final JavaPlugin plugin;
    private final NamespacedKey machineKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey slotKey;
    private final NamespacedKey controlKey;
    private final Map<String, GrabberMachine> machines = new HashMap<>();

    public GrabberManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.machineKey = new NamespacedKey(plugin, "grabber_machine");
        this.typeKey = new NamespacedKey(plugin, "grabber_type");
        this.slotKey = new NamespacedKey(plugin, "grabber_slot");
        this.controlKey = new NamespacedKey(plugin, "grabber_control");
    }

    public void load() {
        machines.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(CONFIG_ROOT);
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            String worldId = section.getString(key + ".world");
            String frontName = section.getString(key + ".front");
            if (worldId == null || frontName == null) {
                continue;
            }

            World world = Bukkit.getWorld(UUID.fromString(worldId));
            if (world == null) {
                continue;
            }

            BlockFace front;
            try {
                front = BlockFace.valueOf(frontName);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (!isHorizontal(front)) {
                continue;
            }

            Location base = new Location(
                    world,
                    section.getInt(key + ".x"),
                    section.getInt(key + ".y"),
                    section.getInt(key + ".z"));
            machines.put(serializeKey(base), new GrabberMachine(base, front));
        }
    }

    public void save() {
        plugin.getConfig().set(CONFIG_ROOT, null);
        ConfigurationSection section = plugin.getConfig().createSection(CONFIG_ROOT);

        int index = 0;
        for (GrabberMachine machine : machines.values()) {
            String path = "grabber-" + index++;
            Location base = machine.baseLocation();
            section.set(path + ".world", base.getWorld().getUID().toString());
            section.set(path + ".x", base.getBlockX());
            section.set(path + ".y", base.getBlockY());
            section.set(path + ".z", base.getBlockZ());
            section.set(path + ".front", machine.front().name());
        }

        plugin.saveConfig();
    }

    public boolean register(Block baseBlock, BlockFace front) {
        if (!isValidMachine(baseBlock, front)) {
            return false;
        }

        String key = serializeKey(baseBlock.getLocation());
        if (machines.containsKey(key)) {
            return false;
        }

        GrabberMachine machine = new GrabberMachine(baseBlock.getLocation(), front);
        machines.put(key, machine);
        spawnDisplays(machine.baseLocation());
        save();
        return true;
    }

    public boolean remove(Location base, boolean dropActivator) {
        GrabberMachine removed = machines.remove(serializeKey(base));
        if (removed == null) {
            return false;
        }

        removeDisplays(base);
        removeAttachedFrame(removed, dropActivator);
        save();
        return true;
    }

    public boolean isMachine(Location base) {
        return machines.containsKey(serializeKey(base));
    }

    public @Nullable GrabberMachine getMachine(Location base) {
        return machines.get(serializeKey(base));
    }

    public Collection<GrabberMachine> getMachines() {
        return Collections.unmodifiableCollection(machines.values());
    }

    public List<GrabberMachine> getMachinesInChunk(World world, int chunkX, int chunkZ) {
        return machines.values().stream()
                .filter(machine -> machine.baseLocation().getWorld().equals(world))
                .filter(machine -> machine.baseLocation().getChunk().getX() == chunkX)
                .filter(machine -> machine.baseLocation().getChunk().getZ() == chunkZ)
                .toList();
    }

    public void spawnAllDisplays() {
        for (GrabberMachine machine : machines.values()) {
            spawnDisplays(machine.baseLocation());
        }
    }

    public void spawnDisplays(Location base) {
        GrabberMachine machine = getMachine(base);
        if (machine == null || !base.isChunkLoaded()) {
            return;
        }

        removeDisplays(base);
        World world = base.getWorld();
        if (world == null) {
            return;
        }

        for (Control control : Control.values()) {
            spawnControlDisplay(machine, control);
        }
        for (int slot = 0; slot < PRIZE_DISPLAY_COUNT; slot++) {
            spawnPrizeDisplay(machine, slot, new ItemStack(Material.AIR));
        }
        spawnChuteDisplay(machine);
        spawnInputDisplay(machine);
        spawnClawCable(machine);
        spawnClawHead(machine);
        spawnStatusDisplay(machine);
    }

    public void removeDisplays(Location base) {
        World world = base.getWorld();
        if (world == null) {
            return;
        }

        String key = serializeKey(base);
        for (Entity entity : world.getNearbyEntities(base.clone().add(0.5, 1.2, 0.5), 3.5, 3.0, 3.5)) {
            String stored = entity.getPersistentDataContainer().get(machineKey, PersistentDataType.STRING);
            if (key.equals(stored)) {
                entity.remove();
            }
        }
    }

    public void setStatusText(Location base, String text) {
        TextDisplay display = getStatusDisplay(base);
        if (display == null || !display.isValid()) {
            return;
        }
        display.text(net.kyori.adventure.text.Component.text(text));
    }

    public void setPrizeItem(Location base, int slot, ItemStack stack) {
        setPrizeItem(base, slot, stack, 0.08, 0.0f, 0.0f, 0.0f);
    }

    public void setPrizeItem(Location base, int slot, ItemStack stack, double depthOffset, float yaw, float pitch, float roll) {
        ItemDisplay display = getPrizeDisplay(base, slot);
        if (display == null || !display.isValid()) {
            GrabberMachine machine = getMachine(base);
            if (machine == null) {
                return;
            }
            spawnPrizeDisplay(machine, slot, stack);
            display = getPrizeDisplay(base, slot);
            if (display == null || !display.isValid()) {
                return;
            }
        }
        display.setItemStack(stack.getType() == Material.AIR ? null : stack.clone());
        GrabberMachine machine = getMachine(base);
        if (machine == null) {
            return;
        }
        display.teleport(getPrizeLocation(machine, slot, depthOffset));
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf()
                        .rotateY((float) Math.toRadians(yaw))
                        .rotateX((float) Math.toRadians(pitch))
                        .rotateZ((float) Math.toRadians(roll)),
                new Vector3f(0.26f, 0.26f, 0.26f),
                new Quaternionf()));
    }

    public void updateClaw(Location base, int col, int row, double depth) {
        updateClaw(base, (double) col, (double) row, depth);
    }

    public void updateClaw(Location base, double col, double row, double depth) {
        GrabberMachine machine = getMachine(base);
        if (machine == null) {
            return;
        }

        ItemDisplay cable = getCableDisplay(base);
        ItemDisplay head = getHeadDisplay(base);
        if (cable == null || head == null || !cable.isValid() || !head.isValid()) {
            return;
        }

        Location cableLocation = getCableLocation(machine, col, row, depth);
        Location headLocation = getHeadLocation(machine, col, row, depth);
        cable.teleport(cableLocation);
        cable.setTransformation(new Transformation(
                new Vector3f(),
                getFlatRotation(machine.front()),
                new Vector3f(0.18f, (float) Math.max(0.26, 0.38f + ((float) depth * 0.78f)), 0.18f),
                new Quaternionf()));

        head.teleport(headLocation);
        head.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf().rotateY((float) Math.toRadians(90)),
                new Vector3f(0.16f, 0.16f, 0.16f),
                new Quaternionf()));
    }

    public Location getClawHeadLocation(Location base, int col, int row, double depth) {
        return getClawHeadLocation(base, (double) col, (double) row, depth);
    }

    public Location getClawHeadLocation(Location base, double col, double row, double depth) {
        GrabberMachine machine = getMachine(base);
        if (machine == null) {
            return base.clone().add(0.5, 1.5, 0.5);
        }
        if (depth <= 0.0 || (col == 0.0 && row == 0.0)) {
            return getInputLocation(machine).clone().add(0.0, -0.24, 0.0);
        }
        return getHeadLocation(machine, col, row, depth);
    }

    public Location getChuteDropLocation(Location base) {
        GrabberMachine machine = getMachine(base);
        if (machine == null) {
            return base.clone().add(0.2, 0.6, 0.2);
        }
        return getInputLocation(machine).clone().add(0.0, 0.18, 0.0);
    }

    public Location getFrontDropLocation(Location base) {
        GrabberMachine machine = getMachine(base);
        if (machine == null) {
            return base.clone().add(0.5, 0.05, 1.05);
        }
        Location location = machine.baseLocation().clone().add(0.5, 0.04, 0.5);
        addFaceOffset(location, machine.front(), 1.14);
        addFaceOffset(location, rotateRight(machine.front()), 0.04);
        addFaceOffset(location, machine.front().getOppositeFace(), 0.04);
        return location;
    }

    public void spawnFloorReward(Location base, ItemStack stack) {
        spawnFloorReward(base, stack, getFrontDropLocation(base));
    }

    public void spawnFloorReward(Location base, ItemStack stack, Location location) {
        removeFloorReward(base);
        GrabberMachine machine = getMachine(base);
        if (machine == null) {
            return;
        }
        World world = base.getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay display = (ItemDisplay) world.spawnEntity(location, EntityType.ITEM_DISPLAY);
        display.setItemStack(stack.clone());
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf().rotateX((float) Math.toRadians(90)),
                new Vector3f(0.36f, 0.36f, 0.36f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "floor_reward");
    }

    public void teleportFloorReward(Location base, Location location) {
        ItemDisplay display = getItemDisplayByType(base, "floor_reward");
        if (display != null && display.isValid()) {
            display.teleport(location);
        }
    }

    public void removeFloorReward(Location base) {
        ItemDisplay display = getItemDisplayByType(base, "floor_reward");
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    public void spawnCarriedItem(Location base, ItemStack stack) {
        removeCarriedItem(base);
        GrabberMachine machine = getMachine(base);
        if (machine == null) {
            return;
        }
        World world = base.getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay display = (ItemDisplay) world.spawnEntity(getHeadLocation(machine, 1, 1, 0.0), EntityType.ITEM_DISPLAY);
        display.setItemStack(stack.clone());
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf().rotateY((float) Math.toRadians(25)),
                new Vector3f(0.24f, 0.24f, 0.24f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "carried");
    }

    public void teleportCarriedItem(Location base, Location location) {
        ItemDisplay display = getItemDisplayByType(base, "carried");
        if (display != null && display.isValid()) {
            display.teleport(location);
        }
    }

    public void removeCarriedItem(Location base) {
        ItemDisplay display = getItemDisplayByType(base, "carried");
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    public boolean isControlDisplay(Entity entity) {
        return entity.getPersistentDataContainer().has(controlKey, PersistentDataType.STRING);
    }

    public @Nullable Control getControl(Entity entity) {
        String control = entity.getPersistentDataContainer().get(controlKey, PersistentDataType.STRING);
        if (control == null) {
            return null;
        }
        try {
            return Control.valueOf(control);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public @Nullable GrabberMachine getMachineForEntity(Entity entity) {
        String stored = entity.getPersistentDataContainer().get(machineKey, PersistentDataType.STRING);
        if (stored == null) {
            return null;
        }
        return machines.get(stored);
    }

    public @Nullable ItemFrame findAttachedFrame(Location base) {
        GrabberMachine machine = getMachine(base);
        if (machine == null) {
            return null;
        }

        World world = base.getWorld();
        if (world == null) {
            return null;
        }

        Block glass = getGlassBlock(machine);
        BlockFace front = machine.front();
        Location frameSearch = glass.getRelative(front).getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : world.getNearbyEntities(frameSearch, 0.7, 0.7, 0.7)) {
            if (entity instanceof ItemFrame frame && frame.getAttachedFace() == front.getOppositeFace()) {
                return frame;
            }
        }
        return null;
    }

    public @Nullable GrabberMachine findMachineByBlock(Block block) {
        for (GrabberMachine machine : machines.values()) {
            if (sameBlock(machine.baseLocation(), block.getLocation())
                    || sameBlock(getGlassBlock(machine).getLocation(), block.getLocation())) {
                return machine;
            }
        }
        return null;
    }

    public boolean isValidMachine(Block baseBlock, BlockFace front) {
        if (baseBlock.getType() != Material.CHISELED_BOOKSHELF || !isHorizontal(front)) {
            return false;
        }

        Block glass = baseBlock.getRelative(BlockFace.UP);
        return glass.getType() == Material.GLASS;
    }

    public void shutdown() {
        for (GrabberMachine machine : machines.values()) {
            removeDisplays(machine.baseLocation());
        }
    }

    private void spawnControlDisplay(GrabberMachine machine, Control control) {
        World world = machine.baseLocation().getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay display = (ItemDisplay) world.spawnEntity(getControlLocation(machine, control), EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(getControlMaterial(control)));
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                getArrowRotation(machine.front(), control),
                new Vector3f(control == Control.DROP ? 0.24f : 0.28f, control == Control.DROP ? 0.24f : 0.28f,
                        control == Control.DROP ? 0.24f : 0.28f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "control");
        display.getPersistentDataContainer().set(controlKey, PersistentDataType.STRING, control.name());
    }

    private void spawnPrizeDisplay(GrabberMachine machine, int slot, ItemStack stack) {
        World world = machine.baseLocation().getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay display = (ItemDisplay) world.spawnEntity(getPrizeLocation(machine, slot, 0.0), EntityType.ITEM_DISPLAY);
        display.setItemStack(stack.getType() == Material.AIR ? null : stack.clone());
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                getFlatRotation(machine.front()),
                new Vector3f(0.26f, 0.26f, 0.26f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "prize");
        display.getPersistentDataContainer().set(slotKey, PersistentDataType.INTEGER, slot);
    }

    private void spawnChuteDisplay(GrabberMachine machine) {
        World world = machine.baseLocation().getWorld();
        if (world == null) {
            return;
        }

        BlockDisplay display = (BlockDisplay) world.spawnEntity(getChuteLocation(machine), EntityType.BLOCK_DISPLAY);
        display.setBlock(Material.CAULDRON.createBlockData());
        display.setBillboard(Display.Billboard.FIXED);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(0.28f, 0.28f, 0.28f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "chute");
    }

    private void spawnInputDisplay(GrabberMachine machine) {
        World world = machine.baseLocation().getWorld();
        if (world == null) {
            return;
        }

        BlockDisplay display = (BlockDisplay) world.spawnEntity(getInputLocation(machine), EntityType.BLOCK_DISPLAY);
        display.setBlock(Material.CAULDRON.createBlockData());
        display.setBillboard(Display.Billboard.FIXED);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(0.28f, 0.28f, 0.28f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "input");
    }

    private void spawnClawCable(GrabberMachine machine) {
        World world = machine.baseLocation().getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay display = (ItemDisplay) world.spawnEntity(getInputLocation(machine), EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(Material.IRON_CHAIN));
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                getFlatRotation(machine.front()),
                new Vector3f(0.4f, 1.0f, 0.4f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "cable");
    }

    private void spawnClawHead(GrabberMachine machine) {
        World world = machine.baseLocation().getWorld();
        if (world == null) {
            return;
        }

        ItemDisplay display = (ItemDisplay) world.spawnEntity(getInputLocation(machine).clone().add(0.0, -0.24, 0.0), EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(Material.ANVIL));
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf().rotateY((float) Math.toRadians(90)),
                new Vector3f(0.16f, 0.16f, 0.16f),
                new Quaternionf()));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "head");
    }

    private void spawnStatusDisplay(GrabberMachine machine) {
        World world = machine.baseLocation().getWorld();
        if (world == null) {
            return;
        }

        TextDisplay display = (TextDisplay) world.spawnEntity(getStatusLocation(machine), EntityType.TEXT_DISPLAY);
        display.setBillboard(Display.Billboard.CENTER);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.text(net.kyori.adventure.text.Component.text("1 Emerald"));
        display.setShadowed(true);
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.baseLocation()));
        display.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, "status");
    }

    private @Nullable ItemDisplay getPrizeDisplay(Location base, int slot) {
        World world = base.getWorld();
        if (world == null) {
            return null;
        }

        String key = serializeKey(base);
        for (Entity entity : world.getNearbyEntities(base.clone().add(0.5, 1.2, 0.5), 3.0, 2.5, 3.0)) {
            if (!(entity instanceof ItemDisplay display)) {
                continue;
            }
            String stored = display.getPersistentDataContainer().get(machineKey, PersistentDataType.STRING);
            String type = display.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
            Integer storedSlot = display.getPersistentDataContainer().get(slotKey, PersistentDataType.INTEGER);
            if (key.equals(stored) && "prize".equals(type) && storedSlot != null && storedSlot == slot) {
                return display;
            }
        }
        return null;
    }

    private @Nullable ItemDisplay getCableDisplay(Location base) {
        return getItemDisplayByType(base, "cable");
    }

    private @Nullable ItemDisplay getHeadDisplay(Location base) {
        return getItemDisplayByType(base, "head");
    }

    private @Nullable TextDisplay getStatusDisplay(Location base) {
        World world = base.getWorld();
        if (world == null) {
            return null;
        }

        String key = serializeKey(base);
        for (Entity entity : world.getNearbyEntities(base.clone().add(0.5, 0.2, 0.5), 3.0, 2.5, 3.0)) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            String stored = display.getPersistentDataContainer().get(machineKey, PersistentDataType.STRING);
            String type = display.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
            if (key.equals(stored) && "status".equals(type)) {
                return display;
            }
        }
        return null;
    }

    private @Nullable ItemDisplay getItemDisplayByType(Location base, String targetType) {
        World world = base.getWorld();
        if (world == null) {
            return null;
        }

        String key = serializeKey(base);
        for (Entity entity : world.getNearbyEntities(base.clone().add(0.5, 1.5, 0.5), 3.0, 2.5, 3.0)) {
            if (!(entity instanceof ItemDisplay display)) {
                continue;
            }
            String stored = display.getPersistentDataContainer().get(machineKey, PersistentDataType.STRING);
            String type = display.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
            if (key.equals(stored) && targetType.equals(type)) {
                return display;
            }
        }
        return null;
    }

    private Location getControlLocation(GrabberMachine machine, Control control) {
        return getShelfSlotLocation(machine, getControlSlot(control));
    }

    private Location getPrizeLocation(GrabberMachine machine, int slot, double depthOffset) {
        BlockFace front = machine.front();
        BlockFace right = rotateRight(front);
        double[][] positions = {
                {-0.28, 0.28}, {-0.14, 0.28}, {0.0, 0.28}, {0.14, 0.28}, {0.28, 0.28},
                {-0.28, 0.14}, {-0.14, 0.14}, {0.0, 0.14}, {0.14, 0.14}, {0.28, 0.14},
                {-0.28, 0.0}, {-0.14, 0.0}, {0.0, 0.0}, {0.14, 0.0}, {0.28, 0.0},
                {-0.28, -0.14}, {-0.14, -0.14}, {0.0, -0.14}, {0.14, -0.14}, {0.28, -0.14},
                {-0.28, -0.28}, {-0.14, -0.28}, {0.0, -0.28}, {0.14, -0.28}, {0.28, -0.28},
                {-0.07, 0.07}, {0.07, 0.07}, {-0.07, -0.07}, {0.07, -0.07}
        };
        int safeSlot = Math.max(0, Math.min(positions.length - 1, slot));
        Location location = machine.baseLocation().clone().add(0.5, 1.04, 0.5);
        addFaceOffset(location, right, positions[safeSlot][0]);
        addFaceOffset(location, front, positions[safeSlot][1] + depthOffset);
        return location;
    }

    private Location getCableLocation(GrabberMachine machine, double col, double row, double depth) {
        return getGridLocation(machine, col, row).add(0.0, 1.30 - (depth * 0.04), 0.0);
    }

    private Location getHeadLocation(GrabberMachine machine, double col, double row, double depth) {
        return getGridLocation(machine, col, row).add(0.0, 1.08 - (depth * 0.42), 0.0);
    }

    private Location getGridLocation(GrabberMachine machine, double col, double row) {
        BlockFace right = rotateRight(machine.front());
        Location location = machine.baseLocation().clone().add(0.5, 0.5, 0.5);
        double lateral = mapRange(col, 0.0, 8.0, 0.32, -0.32);
        double forward = mapRange(row, 0.0, 8.0, 0.26, -0.26);
        addFaceOffset(location, right, lateral);
        addFaceOffset(location, machine.front(), forward);
        return location;
    }

    private double mapRange(double value, double inMin, double inMax, double outMin, double outMax) {
        double clamped = Math.max(inMin, Math.min(inMax, value));
        double progress = (clamped - inMin) / (inMax - inMin);
        return outMin + ((outMax - outMin) * progress);
    }

    private Location getStatusLocation(GrabberMachine machine) {
        Location location = machine.baseLocation().clone().add(0.5, -0.45, 0.5);
        addFaceOffset(location, machine.front(), 1.05);
        return location;
    }

    private Location getChuteLocation(GrabberMachine machine) {
        BlockFace right = rotateRight(machine.front());
        Location location = getShelfSlotLocation(machine, 3);
        addFaceOffset(location, machine.front(), 0.04);
        addFaceOffset(location, right, -0.12);
        location.add(0.0, -0.18, 0.0);
        return location;
    }

    private Location getInputLocation(GrabberMachine machine) {
        BlockFace right = rotateRight(machine.front());
        Location location = getChuteLocation(machine).clone().add(0.0, 0.72, 0.0);
        addFaceOffset(location, machine.front().getOppositeFace(), 0.16);
        addFaceOffset(location, right, 0.04);
        return location;
    }

    private Location getShelfSlotLocation(GrabberMachine machine, int slot) {
        BlockFace right = rotateRight(machine.front());
        int slotRow = slot / 3;
        int slotCol = slot % 3;
        double[] slotX = { 0.265, 0.0, -0.265 };
        double[] slotY = { 0.25, -0.25 };

        Location location = machine.baseLocation().clone().add(0.5, 0.5, 0.5);
        addFaceOffset(location, right, slotX[slotCol]);
        location.add(0.0, slotY[slotRow], 0.0);
        addFaceOffset(location, machine.front(), 0.535);
        return location;
    }

    private Quaternionf getArrowRotation(BlockFace front, Control control) {
        float yaw = switch (front) {
            case NORTH -> (float) Math.toRadians(180);
            case SOUTH -> 0.0f;
            case WEST -> (float) Math.toRadians(90);
            case EAST -> (float) Math.toRadians(-90);
            default -> 0.0f;
        };
        Quaternionf rotation = new Quaternionf().rotateY(yaw);
        return switch (control) {
            case LEFT -> rotation.rotateZ((float) Math.toRadians(45));
            case UP -> rotation.rotateZ((float) Math.toRadians(-45));
            case DOWN -> rotation.rotateZ((float) Math.toRadians(135));
            case DROP -> rotation;
            case RIGHT -> rotation.rotateZ((float) Math.toRadians(-135));
        };
    }

    private Material getControlMaterial(Control control) {
        return control == Control.DROP ? Material.TRIPWIRE_HOOK : Material.ARROW;
    }

    public @Nullable Control getControlForShelfSlot(int slot) {
        for (Control control : Control.values()) {
            if (getControlSlot(control) == slot) {
                return control;
            }
        }
        return null;
    }

    private int getControlSlot(Control control) {
        return switch (control) {
            case LEFT -> 0;
            case UP -> 1;
            case RIGHT -> 2;
            case DOWN -> 4;
            case DROP -> 5;
        };
    }

    private Quaternionf getFlatRotation(BlockFace front) {
        float yaw = switch (front) {
            case NORTH -> (float) Math.toRadians(180);
            case SOUTH -> 0.0f;
            case WEST -> (float) Math.toRadians(90);
            case EAST -> (float) Math.toRadians(-90);
            default -> 0.0f;
        };
        return new Quaternionf().rotateY(yaw);
    }

    private void removeAttachedFrame(GrabberMachine machine, boolean dropActivator) {
        ItemFrame frame = findAttachedFrame(machine.baseLocation());
        if (frame != null) {
            frame.remove();
        }
        if (!dropActivator) {
            return;
        }

        World world = machine.baseLocation().getWorld();
        if (world == null) {
            return;
        }

        Location drop = machine.baseLocation().clone().add(0.5, 1.1, 0.5);
        world.dropItemNaturally(drop, new ItemStack(Material.ITEM_FRAME));
        world.dropItemNaturally(drop, new ItemStack(Material.IRON_CHAIN));
    }

    private Block getGlassBlock(GrabberMachine machine) {
        return machine.baseLocation().getBlock().getRelative(BlockFace.UP);
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

    private boolean isHorizontal(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.EAST || face == BlockFace.WEST;
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

    public record GrabberMachine(Location baseLocation, BlockFace front) {
    }

    public enum Control {
        LEFT,
        RIGHT,
        UP,
        DOWN,
        DROP
    }
}
