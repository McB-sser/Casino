package mcbesser.casino;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class DiceManager {

    private static final String CONFIG_ROOT = "dice-rollers";

    private final JavaPlugin plugin;
    private final NamespacedKey machineKey;
    private final NamespacedKey dieKey;
    private final Map<String, DiceMachine> machines = new HashMap<>();
    private final Map<String, DiceMachine> machineByBlock = new HashMap<>();

    public DiceManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.machineKey = new NamespacedKey(plugin, "dice_machine");
        this.dieKey = new NamespacedKey(plugin, "dice_machine_die");
    }

    public void load() {
        machines.clear();
        machineByBlock.clear();

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

            Location trigger = new Location(
                world,
                section.getInt(key + ".x"),
                section.getInt(key + ".y"),
                section.getInt(key + ".z")
            );

            List<DieEntry> dice = new ArrayList<>();
            ConfigurationSection diceSection = section.getConfigurationSection(key + ".dice");
            if (diceSection == null) {
                continue;
            }

            for (String dieId : diceSection.getKeys(false)) {
                ConfigurationSection dieSection = diceSection.getConfigurationSection(dieId);
                if (dieSection == null) {
                    continue;
                }

                Location dieLocation = new Location(
                    world,
                    dieSection.getInt("x"),
                    dieSection.getInt("y"),
                    dieSection.getInt("z")
                );

                Material buttonMaterial = Material.matchMaterial(dieSection.getString("button", Material.OAK_BUTTON.name()));
                if (buttonMaterial == null) {
                    buttonMaterial = Material.OAK_BUTTON;
                }
                int minValue = dieSection.getInt("min", 1);
                int maxValue = dieSection.getInt("max", 6);
                boolean uniqueInMachine = dieSection.getBoolean("unique", false);
                boolean timeFormat = dieSection.getBoolean("time-format", false);
                int step = Math.max(1, dieSection.getInt("step", 1));
                String colorCode = dieSection.getString("color");
                int face = alignToStep(dieSection.getInt("face", minValue), minValue, maxValue, step);
                dice.add(new DieEntry(dieLocation, buttonMaterial, minValue, maxValue, face, uniqueInMachine, timeFormat, step, colorCode));
            }

            DiceMachine machine = new DiceMachine(trigger, dice);
            machines.put(serializeKey(trigger), machine);
            index(machine);
        }
    }

    public void save() {
        plugin.getConfig().set(CONFIG_ROOT, null);
        ConfigurationSection root = plugin.getConfig().createSection(CONFIG_ROOT);

        int index = 0;
        for (DiceMachine machine : machines.values()) {
            String path = "machine-" + index++;
            Location trigger = machine.triggerLocation();
            root.set(path + ".world", trigger.getWorld().getUID().toString());
            root.set(path + ".x", trigger.getBlockX());
            root.set(path + ".y", trigger.getBlockY());
            root.set(path + ".z", trigger.getBlockZ());

            ConfigurationSection diceSection = root.createSection(path + ".dice");
            int dieIndex = 0;
            for (DieEntry die : machine.dice()) {
                String diePath = "die-" + dieIndex++;
                diceSection.set(diePath + ".x", die.location().getBlockX());
                diceSection.set(diePath + ".y", die.location().getBlockY());
                diceSection.set(diePath + ".z", die.location().getBlockZ());
                diceSection.set(diePath + ".button", die.buttonMaterial().name());
                diceSection.set(diePath + ".min", die.minValue());
                diceSection.set(diePath + ".max", die.maxValue());
                diceSection.set(diePath + ".uniq\u00fc", die.uniqueInMachine());
                diceSection.set(diePath + ".time-format", die.timeFormat());
                diceSection.set(diePath + ".step", die.step());
                diceSection.set(diePath + ".color", die.colorCode());
                diceSection.set(diePath + ".face", die.face());
            }
        }

        plugin.saveConfig();
    }

    public void spawnAllDisplays() {
        for (DiceMachine machine : machines.values()) {
            spawnDisplays(machine);
        }
    }

    public void shutdown() {
        for (DiceMachine machine : machines.values()) {
            removeDisplays(machine);
        }
    }

    public boolean isDiceBlock(Location location) {
        return machineByBlock.containsKey(serializeKey(location));
    }

    public @Nullable DiceMachine findMachine(Location location) {
        return machineByBlock.get(serializeKey(location));
    }

    public @Nullable DiceMachine findMachineByTrigger(Location location) {
        return machines.get(serializeKey(location));
    }

    public boolean register(Location triggerLocation, List<DieEntry> dice) {
        String key = serializeKey(triggerLocation);
        if (machines.containsKey(key) || dice.isEmpty()) {
            return false;
        }

        DiceMachine machine = new DiceMachine(triggerLocation, new ArrayList<>(dice));
        machines.put(key, machine);
        index(machine);
        spawnDisplays(machine);
        save();
        return true;
    }

    public boolean removeMachine(DiceMachine machine, boolean dropItems) {
        DiceMachine removed = machines.remove(serializeKey(machine.triggerLocation()));
        if (removed == null) {
            return false;
        }

        deindex(removed);
        removeDisplays(removed);
        if (dropItems) {
            dropBuildItems(removed);
        }
        save();
        return true;
    }

    public void updateFaces(DiceMachine machine, List<Integer> faces) {
        DiceMachine replacement = createUpdatedMachine(machine, faces);
        String key = serializeKey(machine.triggerLocation());
        machines.put(key, replacement);
        deindex(machine);
        index(replacement);
        refreshDisplays(replacement);
        save();
    }

    public void previewFaces(DiceMachine machine, List<Integer> faces) {
        refreshDisplays(createUpdatedMachine(machine, faces));
    }

    private DiceMachine createUpdatedMachine(DiceMachine machine, List<Integer> faces) {
        List<DieEntry> updated = new ArrayList<>();
        for (int i = 0; i < machine.dice().size(); i++) {
            DieEntry die = machine.dice().get(i);
            int face = i < faces.size()
                ? alignToStep(faces.get(i), die.minValue(), die.maxValue(), die.step())
                : die.face();
            updated.add(new DieEntry(
                die.location(),
                die.buttonMaterial(),
                die.minValue(),
                die.maxValue(),
                face,
                die.uniqueInMachine(),
                die.timeFormat(),
                die.step(),
                die.colorCode()
            ));
        }
        return new DiceMachine(machine.triggerLocation(), updated);
    }

    public void refreshDisplays(DiceMachine machine) {
        for (DieEntry die : machine.dice()) {
            TextDisplay display = getDisplay(die.location());
            if (display == null || !display.isValid()) {
                spawnDisplay(machine, die);
                continue;
            }
            display.text(createFaceComponent(die));
            display.setTransformation(createDisplayTransformation(die));
        }
    }

    public void spawnDisplays(DiceMachine machine) {
        for (DieEntry die : machine.dice()) {
            spawnDisplay(machine, die);
        }
    }

    public Collection<DiceMachine> getMachinesInChunk(World world, int chunkX, int chunkZ) {
        return machines.values().stream()
            .filter(machine -> machine.triggerLocation().getWorld().equals(world))
            .filter(machine -> machine.dice().stream().anyMatch(die ->
                die.location().getChunk().getX() == chunkX && die.location().getChunk().getZ() == chunkZ))
            .toList();
    }

    private void index(DiceMachine machine) {
        for (DieEntry die : machine.dice()) {
            machineByBlock.put(serializeKey(die.location()), machine);
        }
    }

    private void deindex(DiceMachine machine) {
        for (DieEntry die : machine.dice()) {
            machineByBlock.remove(serializeKey(die.location()));
        }
    }

    private void dropBuildItems(DiceMachine machine) {
        World world = machine.triggerLocation().getWorld();
        if (world == null) {
            return;
        }

        for (DieEntry die : machine.dice()) {
            Location dropLocation = die.location().clone().add(0.5, 1.0, 0.5);
            world.dropItemNaturally(dropLocation, new ItemStack(Material.ITEM_FRAME));
            world.dropItemNaturally(dropLocation, new ItemStack(die.buttonMaterial()));
        }
    }

    private void removeDisplays(DiceMachine machine) {
        for (DieEntry die : machine.dice()) {
            TextDisplay display = getDisplay(die.location());
            if (display != null) {
                display.remove();
            }
        }
    }

    private void spawnDisplay(DiceMachine machine, DieEntry die) {
        World world = die.location().getWorld();
        if (world == null || !die.location().isChunkLoaded()) {
            return;
        }

        TextDisplay existing = getDisplay(die.location());
        if (existing != null) {
            existing.remove();
        }

        TextDisplay display = (TextDisplay) world.spawnEntity(getDisplayLocation(die.location()), EntityType.TEXT_DISPLAY);
        display.setBillboard(Display.Billboard.CENTER);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setShadowed(true);
        display.setSeeThrough(false);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.text(createFaceComponent(die));
        display.setTransformation(createDisplayTransformation(die));
        display.getPersistentDataContainer().set(machineKey, PersistentDataType.STRING, serializeKey(machine.triggerLocation()));
        display.getPersistentDataContainer().set(dieKey, PersistentDataType.STRING, serializeKey(die.location()));
    }

    private @Nullable TextDisplay getDisplay(Location blockLocation) {
        World world = blockLocation.getWorld();
        if (world == null) {
            return null;
        }

        String blockKey = serializeKey(blockLocation);
        for (Entity entity : world.getNearbyEntities(getDisplayLocation(blockLocation), 0.35, 0.5, 0.35)) {
            if (!(entity instanceof TextDisplay display)) {
                continue;
            }
            String stored = display.getPersistentDataContainer().get(dieKey, PersistentDataType.STRING);
            if (blockKey.equals(stored)) {
                return display;
            }
        }
        return null;
    }

    private Location getDisplayLocation(Location blockLocation) {
        return blockLocation.clone().add(0.5, 1.35, 0.5);
    }

    private Component createFaceComponent(DieEntry die) {
        return Component.text(formatFace(die), resolveDisplayColor(die.location(), die.colorCode()));
    }

    private Transformation createDisplayTransformation(DieEntry die) {
        float scale = isStandardDiceIcon(die) ? 3.4f : 1.7f;
        return new Transformation(
            new Vector3f(0.0f, 0.0f, 0.0f),
            new Quaternionf(),
            new Vector3f(scale, scale, scale),
            new Quaternionf()
        );
    }

    private String formatFace(DieEntry die) {
        if (!die.timeFormat()) {
            if (isStandardDiceIcon(die)) {
                return switch (die.face()) {
                    case 1 -> "\u2680";
                    case 2 -> "\u2681";
                    case 3 -> "\u2682";
                    case 4 -> "\u2683";
                    case 5 -> "\u2684";
                    case 6 -> "\u2685";
                    default -> String.valueOf(die.face());
                };
            }
            return String.valueOf(die.face());
        }

        int hours = Math.floorDiv(die.face(), 60);
        int minutes = Math.floorMod(die.face(), 60);
        return String.format("%02d:%02d", hours, minutes);
    }

    private boolean isStandardDiceIcon(DieEntry die) {
        return die.minValue() == 1 && die.maxValue() == 6 && die.step() == 1;
    }

    private TextColor resolveDisplayColor(Location location, @Nullable String colorCode) {
        TextColor customColor = parseColorCode(colorCode);
        if (customColor != null) {
            return customColor;
        }

        Material material = location.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getType();
        return switch (material) {
            case WHITE_WOOL -> TextColor.color(0xF9FFFE);
            case ORANGE_WOOL -> TextColor.color(0xF9801D);
            case MAGENTA_WOOL -> TextColor.color(0xC74EBD);
            case LIGHT_BLUE_WOOL -> TextColor.color(0x3AB3DA);
            case YELLOW_WOOL -> TextColor.color(0xFED83D);
            case LIME_WOOL -> TextColor.color(0x80C71F);
            case PINK_WOOL -> TextColor.color(0xF38BAA);
            case GRAY_WOOL -> TextColor.color(0x474F52);
            case LIGHT_GRAY_WOOL -> TextColor.color(0x9D9D97);
            case CYAN_WOOL -> TextColor.color(0x169C9C);
            case PURPLE_WOOL -> TextColor.color(0x8932B8);
            case BLUE_WOOL -> TextColor.color(0x3C44AA);
            case BROWN_WOOL -> TextColor.color(0x835432);
            case GREEN_WOOL -> TextColor.color(0x5E7C16);
            case RED_WOOL -> TextColor.color(0xB02E26);
            case BLACK_WOOL -> TextColor.color(0x1D1D21);
            default -> NamedTextColor.GOLD;
        };
    }

    private @Nullable TextColor parseColorCode(@Nullable String colorCode) {
        if (colorCode == null || colorCode.isBlank()) {
            return null;
        }

        String normalized = colorCode.trim();
        if (!normalized.startsWith("#") && normalized.matches("[0-9a-fA-F]{6}")) {
            normalized = "#" + normalized;
        }

        TextColor hexColor = TextColor.fromHexString(normalized);
        if (hexColor != null) {
            return hexColor;
        }

        return switch (normalized.toLowerCase(java.util.Locale.ROOT)) {
            case "black" -> NamedTextColor.BLACK;
            case "dark_blue", "darkblue" -> NamedTextColor.DARK_BLUE;
            case "dark_green", "darkgreen" -> NamedTextColor.DARK_GREEN;
            case "dark_aqua", "darkaqua", "dark_cyan", "darkcyan" -> NamedTextColor.DARK_AQUA;
            case "dark_red", "darkred" -> NamedTextColor.DARK_RED;
            case "dark_purple", "darkpurple" -> NamedTextColor.DARK_PURPLE;
            case "gold", "orange" -> NamedTextColor.GOLD;
            case "gray", "grey" -> NamedTextColor.GRAY;
            case "dark_gray", "darkgray", "dark_grey", "darkgrey" -> NamedTextColor.DARK_GRAY;
            case "blue" -> NamedTextColor.BLUE;
            case "green" -> NamedTextColor.GREEN;
            case "aqua", "cyan" -> NamedTextColor.AQUA;
            case "red" -> NamedTextColor.RED;
            case "light_purple", "lightpurple", "magenta", "pink" -> NamedTextColor.LIGHT_PURPLE;
            case "yellow" -> NamedTextColor.YELLOW;
            case "white" -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    private static int clampToRange(int value, int minValue, int maxValue) {
        int low = Math.min(minValue, maxValue);
        int high = Math.max(minValue, maxValue);
        return Math.max(low, Math.min(high, value));
    }

    private static int alignToStep(int value, int minValue, int maxValue, int step) {
        int clamped = clampToRange(value, minValue, maxValue);
        int safeStep = Math.max(1, step);
        int offset = clamped - minValue;
        int aligned = minValue + Math.floorDiv(offset, safeStep) * safeStep;
        return clampToRange(aligned, minValue, maxValue);
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    public record DiceMachine(Location triggerLocation, List<DieEntry> dice) {
        public DiceMachine {
            dice = Collections.unmodifiableList(new ArrayList<>(dice));
        }
    }

    public record DieEntry(Location location, Material buttonMaterial, int minValue, int maxValue, int face, boolean uniqueInMachine, boolean timeFormat, int step, @Nullable String colorCode) {
        public DieEntry {
            int low = Math.min(minValue, maxValue);
            int high = Math.max(minValue, maxValue);
            minValue = low;
            maxValue = high;
            step = Math.max(1, step);
            face = alignToStep(face, minValue, maxValue, step);
        }
    }
}
