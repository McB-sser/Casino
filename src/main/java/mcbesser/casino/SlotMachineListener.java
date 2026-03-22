package mcbesser.casino;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class SlotMachineListener implements Listener {

    private static final int REEL_COUNT = 3;
    private static final List<Material> SYMBOLS = List.of(
        Material.DIAMOND,
        Material.EMERALD,
        Material.GOLD_INGOT,
        Material.IRON_INGOT,
        Material.REDSTONE,
        Material.LAPIS_LAZULI,
        Material.AMETHYST_SHARD,
        Material.BELL,
        Material.NETHER_STAR
    );

    private final JavaPlugin plugin;
    private final SlotMachineManager manager;

    public SlotMachineListener(JavaPlugin plugin, SlotMachineManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack handItem = event.getItem();

        if (clicked.getType() == manager.getShelfMaterial()) {
            Block lectern = clicked.getRelative(BlockFace.DOWN);
            if (lectern.getType() == Material.LECTERN
                && handItem != null
                && handItem.getType() == Material.LIGHTNING_ROD
                && manager.isValidStructure(lectern)
                && !manager.isMachine(lectern.getLocation())) {
                event.setCancelled(true);

                if (manager.registerMachine(lectern)) {
                    if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                        handItem.subtract(1);
                    }
                    player.sendMessage(Component.text("SlotMachine erstellt.", NamedTextColor.GREEN));
                    player.playSound(clicked.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0f, 1.2f);
                }
                return;
            }

            if (lectern.getType() == Material.LECTERN && manager.isMachine(lectern.getLocation())) {
                event.setCancelled(true);
                startSpin(player, lectern);
            }
            return;
        }

        if (clicked.getType() == Material.LECTERN && manager.isMachine(clicked.getLocation())) {
            event.setCancelled(true);
            startSpin(player, clicked);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (manager.isMachine(block.getLocation())) {
            manager.removeMachine(block.getLocation(), true);
            return;
        }

        Block below = block.getRelative(BlockFace.DOWN);
        if (block.getType() == manager.getShelfMaterial()
            && below.getType() == Material.LECTERN
            && manager.isMachine(below.getLocation())) {
            manager.removeMachine(below.getLocation(), true);
        }
    }

    private void startSpin(Player player, Block lecternBlock) {
        Location machineLocation = lecternBlock.getLocation();
        if (!manager.beginSpin(machineLocation)) {
            player.sendMessage(Component.text("Der Automat laeuft bereits.", NamedTextColor.YELLOW));
            return;
        }

        if (manager.getShelfInventory(machineLocation) == null) {
            manager.endSpin(machineLocation);
            return;
        }

        ItemDisplay handle = manager.findHandle(machineLocation);

        new BukkitRunnable() {
            private int tick;
            private final Material[] finalSymbols = new Material[REEL_COUNT];
            private final Material[] visibleSymbols = new Material[] { randomSymbol(), randomSymbol(), randomSymbol() };
            private final BlockFace front = manager.getFrontFace(lecternBlock);

            @Override
            public void run() {
                if (!lecternBlock.getChunk().isLoaded()
                    || !manager.isMachine(machineLocation)
                    || manager.getShelfInventory(machineLocation) == null) {
                    cleanup();
                    return;
                }

                tick++;
                animateHandle(handle, front, tick);
                updateShelf();
                playSpinSound(lecternBlock.getLocation(), tick);

                if (tick >= 30) {
                    finishSpin(player, lecternBlock.getLocation().add(0.5, 1.0, 0.5), finalSymbols);
                    cleanup();
                }
            }

            private void updateShelf() {
                boolean[] rolling = {
                    tick < 26,
                    tick < 28,
                    tick < 30
                };

                Material carry = randomSymbol();
                for (int i = REEL_COUNT - 1; i >= 0; i--) {
                    if (!rolling[i]) {
                        if (finalSymbols[i] == null) {
                            finalSymbols[i] = visibleSymbols[i];
                        }
                        visibleSymbols[i] = finalSymbols[i];
                        continue;
                    }

                    Material nextCarry = visibleSymbols[i];
                    visibleSymbols[i] = carry;
                    carry = nextCarry;
                }

                manager.setShelfContents(machineLocation, new ItemStack[] {
                    new ItemStack(visibleSymbols[0]),
                    new ItemStack(visibleSymbols[1]),
                    new ItemStack(visibleSymbols[2])
                });
            }

            private void cleanup() {
                cancel();
                manager.setShelfContents(machineLocation, new ItemStack[] {
                    new ItemStack(finalSymbols[0] != null ? finalSymbols[0] : visibleSymbols[0]),
                    new ItemStack(finalSymbols[1] != null ? finalSymbols[1] : visibleSymbols[1]),
                    new ItemStack(finalSymbols[2] != null ? finalSymbols[2] : visibleSymbols[2])
                });
                if (handle != null && handle.isValid()) {
                    handle.setTransformation(manager.createHandleTransformation(front, false));
                }
                manager.endSpin(machineLocation);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void animateHandle(ItemDisplay handle, BlockFace front, int tick) {
        if (handle == null || !handle.isValid()) {
            return;
        }

        boolean pulled = tick <= 4;
        handle.setTransformation(manager.createHandleTransformation(front, pulled));
        if (tick == 1) {
            handle.getWorld().playSound(handle.getLocation(), Sound.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.9f, 0.7f);
        }
    }

    private void playSpinSound(Location location, int tick) {
        float basePitch = 0.55f + ((tick % 3) * 0.05f);
        location.getWorld().playSound(location, Sound.BLOCK_CHAIN_STEP, SoundCategory.BLOCKS, 0.55f, basePitch);
        location.getWorld().playSound(location, Sound.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.25f, 0.65f);
        if (tick % 4 == 0) {
            location.getWorld().playSound(location, Sound.BLOCK_BAMBOO_WOOD_BUTTON_CLICK_OFF, SoundCategory.BLOCKS, 0.45f, 0.6f);
        }
    }

    private void finishSpin(Player player, Location machineCenter, Material[] finalSymbols) {
        boolean jackpot = finalSymbols[0] == finalSymbols[1] && finalSymbols[1] == finalSymbols[2];
        if (jackpot) {
            player.sendMessage(Component.text("Jackpot mit " + formatSymbol(finalSymbols[0]) + "!", NamedTextColor.GOLD));
            player.playSound(machineCenter, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.1f);
            player.playSound(machineCenter, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.0f);
            return;
        }

        player.playSound(machineCenter, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.PLAYERS, 0.7f, 0.9f);
    }

    private Material randomSymbol() {
        return SYMBOLS.get(ThreadLocalRandom.current().nextInt(SYMBOLS.size()));
    }

    private String formatSymbol(Material material) {
        return material.name().toLowerCase().replace('_', ' ');
    }
}
