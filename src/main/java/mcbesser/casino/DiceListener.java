package mcbesser.casino;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class DiceListener implements Listener {

    private final JavaPlugin plugin;
    private final DiceManager manager;
    private final Set<String> activeRolls = new HashSet<>();

    public DiceListener(JavaPlugin plugin, DiceManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onTopFrameInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }

        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (!isButtonMaterial(hand.getType()) || frame.getAttachedFace() != BlockFace.DOWN) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> tryRegisterFromTopFrame(frame));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || !isButtonMaterial(clicked.getType())) {
            return;
        }

        Block triggerBlock = getAttachedBaseBlock(clicked);
        if (triggerBlock == null || triggerBlock.getType() != Material.CHISELED_STONE_BRICKS) {
            return;
        }

        event.setCancelled(true);

        DiceManager.DiceMachine machine = manager.findMachineByTrigger(triggerBlock.getLocation());
        if (machine == null) {
            if (!tryRegisterFromBlock(triggerBlock)) {
                return;
            }
            return;
        }

        if (machine == null) {
            return;
        }

        String key = serializeKey(machine.triggerLocation());
        if (!activeRolls.add(key)) {
            return;
        }

        rollDice(machine, event.getPlayer(), key);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        DiceManager.DiceMachine machine = manager.findMachine(event.getBlock().getLocation());
        if (machine == null) {
            return;
        }

        activeRolls.remove(serializeKey(machine.triggerLocation()));
        manager.removeMachine(machine, true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!chunk.isLoaded()) {
                return;
            }
            for (DiceManager.DiceMachine machine : manager.getMachinesInChunk(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
                manager.spawnDisplays(machine);
            }
        }, CasinoDisplayUtil.chunkLoadDisplayDelay(plugin, chunk));
    }

    private void rollDice(DiceManager.DiceMachine machine, Player player, String key) {
        Location soundLocation = machine.triggerLocation().clone().add(0.5, 0.5, 0.5);
        soundLocation.getWorld().playSound(soundLocation, Sound.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 0.8f, 0.9f);
        soundLocation.getWorld().playSound(soundLocation, Sound.BLOCK_CHAIN_HIT, SoundCategory.BLOCKS, 0.45f, 0.7f);

        new BukkitRunnable() {
            private int step;
            private List<Integer> finalFaces = List.of();

            @Override
            public void run() {
                DiceManager.DiceMachine current = manager.findMachineByTrigger(machine.triggerLocation());
                if (current == null) {
                    activeRolls.remove(key);
                    cancel();
                    return;
                }

                step++;
                finalFaces = randomFaces(current);
                manager.previewFaces(current, finalFaces);
                soundLocation.getWorld().playSound(soundLocation, Sound.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.55f, 0.78f + (step * 0.025f));
                soundLocation.getWorld().playSound(soundLocation, Sound.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 0.35f, 0.8f + (step * 0.03f));

                if (step >= 20) {
                    DiceManager.DiceMachine finished = manager.findMachineByTrigger(machine.triggerLocation());
                    activeRolls.remove(key);
                    cancel();
                    if (finished == null) {
                        return;
                    }

                    manager.updateFaces(finished, finalFaces);
                    DiceManager.DiceMachine resolved = manager.findMachineByTrigger(machine.triggerLocation());
                    if (resolved == null) {
                        return;
                    }

                    soundLocation.getWorld().playSound(soundLocation, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.8f, 1.2f);
                }
            }
        }.runTaskTimer(plugin, 8L, 2L);
    }

    private boolean tryRegisterFromTopFrame(ItemFrame frame) {
        if (!frame.isValid() || frame.getAttachedFace() != BlockFace.DOWN || !isButtonMaterial(frame.getItem().getType())) {
            return false;
        }

        Block block = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
        return tryRegisterFromBlock(block);
    }

    private boolean tryRegisterFromBlock(Block seedBlock) {
        if (seedBlock.getType() != Material.CHISELED_STONE_BRICKS) {
            return false;
        }
        if (manager.findMachine(seedBlock.getLocation()) != null) {
            return true;
        }

        Block triggerBlock = findTriggerBlock(seedBlock);
        if (triggerBlock == null) {
            return false;
        }

        List<DiceManager.DieEntry> dice = discoverDice(triggerBlock);
        if (dice.isEmpty() || !manager.register(triggerBlock.getLocation(), dice)) {
            return false;
        }

        triggerBlock.getWorld().playSound(triggerBlock.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 0.8f, 1.25f);
        return true;
    }

    private Block findTriggerBlock(Block start) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            Block current = queue.removeFirst();
            String key = serializeKey(current.getLocation());
            if (!visited.add(key) || current.getType() != Material.CHISELED_STONE_BRICKS) {
                continue;
            }

            if (hasSideButton(current) && findTopFrame(current) != null) {
                return current;
            }

            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                Block neighbor = current.getRelative(face);
                if (neighbor.getType() == Material.CHISELED_STONE_BRICKS) {
                    queue.add(neighbor);
                }
            }
        }

        return null;
    }

    private List<DiceManager.DieEntry> discoverDice(Block triggerBlock) {
        ItemFrame triggerFrame = findTopFrame(triggerBlock);
        if (triggerFrame == null) {
            return List.of();
        }

        List<DiceManager.DieEntry> dice = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(triggerBlock);

        while (!queue.isEmpty()) {
            Block current = queue.removeFirst();
            String key = serializeKey(current.getLocation());
            if (!visited.add(key)) {
                continue;
            }

            if (!isDieCandidate(current)) {
                continue;
            }

            if (current != triggerBlock && hasSideButton(current)) {
                continue;
            }

            ItemFrame frame = findTopFrame(current);
            ItemStack frameItem = frame.getItem();
            IntRange range = findNumberRange(current);
            dice.add(new DiceManager.DieEntry(
                current.getLocation(),
                frameItem.getType(),
                range.minValue(),
                range.maxValue(),
                randomFace(range.minValue(), range.maxValue()),
                range.uniqueInMachine(),
                range.timeFormat(),
                range.step(),
                range.colorCode()
            ));
            frame.remove();

            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                Block neighbor = current.getRelative(face);
                if (neighbor.getType() == Material.CHISELED_STONE_BRICKS) {
                    queue.add(neighbor);
                }
            }
        }

        return dice;
    }

    private boolean isDieCandidate(Block block) {
        return block.getType() == Material.CHISELED_STONE_BRICKS && findTopFrame(block) != null;
    }

    private boolean hasSideButton(Block block) {
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block side = block.getRelative(face);
            if (!isButtonMaterial(side.getType()) || !(side.getBlockData() instanceof Switch buttonData)) {
                continue;
            }

            if (buttonData.getFace() == Switch.Face.WALL && buttonData.getFacing() == face) {
                return true;
            }
        }
        return false;
    }

    private ItemFrame findTopFrame(Block block) {
        for (org.bukkit.entity.Entity entity : block.getWorld().getNearbyEntities(block.getLocation().clone().add(0.5, 1.0, 0.5), 0.45, 0.45, 0.45)) {
            if (!(entity instanceof ItemFrame frame)) {
                continue;
            }

            if (frame.getAttachedFace() != BlockFace.DOWN) {
                continue;
            }

            ItemStack item = frame.getItem();
            if (isButtonMaterial(item.getType())) {
                return frame;
            }
        }
        return null;
    }

    private Block getAttachedBaseBlock(Block buttonBlock) {
        if (!(buttonBlock.getBlockData() instanceof Switch buttonData)) {
            return null;
        }
        if (buttonData.getFace() != Switch.Face.WALL) {
            return null;
        }
        return buttonBlock.getRelative(buttonData.getFacing().getOppositeFace());
    }

    private IntRange findNumberRange(Block block) {
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN, BlockFace.UP)) {
            Block relative = block.getRelative(face);
            if (!(relative.getState() instanceof Sign sign)) {
                continue;
            }

            IntRange range = parseRange(sign);
            if (range != null) {
                return range;
            }
        }
        return new IntRange(1, 6, false, false, 1, null);
    }

    private IntRange parseRange(Sign sign) {
        String text = java.util.stream.Stream.of(sign.getSide(Side.FRONT), sign.getSide(Side.BACK))
            .flatMap(side -> side.lines().stream())
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .collect(Collectors.joining(" "));

        boolean uniqueInMachine = text.contains("#");
        int step = parseStep(text);
        String colorCode = parseColorCode(text);
        java.util.regex.Matcher timeMatcher = java.util.regex.Pattern.compile("#?\\s*(\\d{2}:\\d{2})\\s*-\\s*(\\d{2}:\\d{2})(?:\\s*\\|\\s*\\d+)?").matcher(text);
        if (timeMatcher.find()) {
            Integer first = parseTimeValue(timeMatcher.group(1));
            Integer second = parseTimeValue(timeMatcher.group(2));
            if (first == null || second == null) {
                return null;
            }
            return new IntRange(Math.min(first, second), Math.max(first, second), uniqueInMachine, true, step, colorCode);
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("#?\\s*(-?\\d+)\\s*-\\s*(-?\\d+)(?:\\s*\\|\\s*\\d+)?").matcher(text);
        if (!matcher.find()) {
            return null;
        }

        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        return new IntRange(Math.min(first, second), Math.max(first, second), uniqueInMachine, false, step, colorCode);
    }

    private List<Integer> randomFaces(DiceManager.DiceMachine machine) {
        List<Integer> faces = new ArrayList<>(java.util.Collections.nCopies(machine.dice().size(), 1));
        Set<Integer> usedUniqueValues = new HashSet<>();

        List<Map.Entry<Integer, DiceManager.DieEntry>> orderedDice = new ArrayList<>();
        for (int i = 0; i < machine.dice().size(); i++) {
            orderedDice.add(Map.entry(i, machine.dice().get(i)));
        }

        orderedDice.sort(Comparator.comparingInt(entry -> rangeSize(entry.getValue())));
        for (Map.Entry<Integer, DiceManager.DieEntry> entry : orderedDice) {
            DiceManager.DieEntry die = entry.getValue();
            int rolledValue = die.uniqueInMachine()
                ? randomUniqueFace(die, usedUniqueValues)
                : randomFace(die);
            faces.set(entry.getKey(), rolledValue);
            if (die.uniqueInMachine()) {
                usedUniqueValues.add(rolledValue);
            }
        }

        return faces;
    }

    private int randomUniqueFace(DiceManager.DieEntry die, Set<Integer> usedUniqueValues) {
        List<Integer> available = new ArrayList<>();
        for (int value = die.minValue(); value <= die.maxValue(); value += die.step()) {
            if (!usedUniqueValues.contains(value)) {
                available.add(value);
            }
        }

        if (available.isEmpty()) {
            return randomFace(die);
        }

        return available.get(ThreadLocalRandom.current().nextInt(available.size()));
    }

    private int rangeSize(DiceManager.DieEntry die) {
        return ((die.maxValue() - die.minValue()) / die.step()) + 1;
    }

    private int randomFace(DiceManager.DieEntry die) {
        return randomFace(die.minValue(), die.maxValue(), die.step());
    }

    private int randomFace(int minValue, int maxValue) {
        return randomFace(minValue, maxValue, 1);
    }

    private int randomFace(int minValue, int maxValue, int step) {
        int low = Math.min(minValue, maxValue);
        int high = Math.max(minValue, maxValue);
        int safeStep = Math.max(1, step);
        int count = ((high - low) / safeStep) + 1;
        return low + (ThreadLocalRandom.current().nextInt(count) * safeStep);
    }

    private int parseStep(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\|\\s*(\\d+)").matcher(text);
        if (!matcher.find()) {
            return 1;
        }
        return Math.max(1, Integer.parseInt(matcher.group(1)));
    }

    private String parseColorCode(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[\\s*([^\\]]+?)\\s*\\]").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).trim();
    }

    private Integer parseTimeValue(String input) {
        String[] parts = input.split(":");
        if (parts.length != 2) {
            return null;
        }

        int hours;
        int minutes;
        try {
            hours = Integer.parseInt(parts[0]);
            minutes = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            return null;
        }

        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
            return null;
        }

        return (hours * 60) + minutes;
    }

    private boolean isButtonMaterial(Material material) {
        return Tag.BUTTONS.isTagged(material);
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private record IntRange(int minValue, int maxValue, boolean uniqueInMachine, boolean timeFormat, int step, String colorCode) {
    }
}
