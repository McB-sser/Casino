package mcbesser.casino;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ChiseledBookshelf;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class GrabberListener implements Listener {

    private static final int ENTRY_COST = 1;
    private static final double MAX_DEPTH = 0.98;
    private static final double SUCCESS_GRAB_DEPTH = 0.42;
    private static final int MOVE_STEPS = 9;
    private static final int DROP_STEPS = 36;
    private static final List<ItemStack> PRIZE_POOL = List.of(
            new ItemStack(Material.EMERALD, 2),
            new ItemStack(Material.DIAMOND),
            new ItemStack(Material.GOLD_INGOT, 3),
            new ItemStack(Material.IRON_INGOT, 5),
            new ItemStack(Material.AMETHYST_SHARD, 4),
            new ItemStack(Material.FIRE_CHARGE, 2),
            new ItemStack(Material.ENDER_PEARL, 2),
            new ItemStack(Material.GOLDEN_APPLE),
            new ItemStack(Material.EXPERIENCE_BOTTLE, 8),
            new ItemStack(Material.PRISMARINE_CRYSTALS, 5),
            new ItemStack(Material.SLIME_BALL, 4));

    private final JavaPlugin plugin;
    private final GrabberManager manager;
    private final java.util.Map<String, GrabberState> states = new java.util.HashMap<>();

    public GrabberListener(JavaPlugin plugin, GrabberManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onActivatorPlaced(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }

        if (event.getPlayer().getInventory().getItemInMainHand().getType() != Material.IRON_CHAIN) {
            return;
        }

        Block glass = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
        Block base = glass.getRelative(BlockFace.DOWN);
        BlockFace front = frame.getAttachedFace().getOppositeFace();
        if (glass.getType() != Material.GLASS || base.getType() != Material.CHISELED_BOOKSHELF || manager.isMachine(base.getLocation())) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (glass.getType() != Material.GLASS || base.getType() != Material.CHISELED_BOOKSHELF) {
                return;
            }
            if (frame.getItem() == null || frame.getItem().getType() != Material.IRON_CHAIN) {
                return;
            }
            if (!manager.register(base, front)) {
                return;
            }

            frame.remove();
            createState(base.getLocation());
            base.getWorld().playSound(base.getLocation(), Sound.BLOCK_CHAIN_PLACE, SoundCategory.BLOCKS, 0.8f, 1.1f);
        });
    }

    @EventHandler
    public void onControlClick(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !manager.isControlDisplay(event.getRightClicked())) {
            return;
        }

        event.setCancelled(true);
        GrabberManager.GrabberMachine machine = manager.getMachineForEntity(event.getRightClicked());
        GrabberManager.Control control = manager.getControl(event.getRightClicked());
        if (machine == null || control == null) {
            return;
        }

        GrabberState state = getOrCreateState(machine.baseLocation());
        if (state.busy || state.moving) {
            return;
        }
        if (state.ownerId == null) {
            event.getPlayer().sendMessage(Component.text("Starte den Greifarm zuerst mit 1 Emerald am Regal.", NamedTextColor.YELLOW));
            return;
        }
        if (!state.ownerId.equals(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendMessage(Component.text("Dieser Greifarm wird gerade von einem anderen Spieler gesteuert.", NamedTextColor.YELLOW));
            return;
        }

        if (control == GrabberManager.Control.DROP) {
            runGrab(event.getPlayer(), machine, state);
            return;
        }

        moveControl(machine, state, control);
    }

    @EventHandler
    public void onBookshelfInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.CHISELED_BOOKSHELF) {
            return;
        }

        GrabberManager.GrabberMachine machine = manager.getMachine(clicked.getLocation());
        if (machine == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        GrabberState state = getOrCreateState(machine.baseLocation());
        if (state.ownerId != null && plugin.getServer().getPlayer(state.ownerId) == null) {
            state.ownerId = null;
        }

        int localSlot = -1;
        if (clicked.getState() instanceof ChiseledBookshelf shelf && event.getClickedPosition() != null) {
            localSlot = shelf.getSlot(event.getClickedPosition());
        }

        GrabberManager.Control shelfControl = manager.getControlForShelfSlot(localSlot);
        if (state.ownerId != null) {
            handleStartedInteraction(player, machine, state, shelfControl);
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.EMERALD || hand.getAmount() < ENTRY_COST) {
            openInfoBook(player);
            return;
        }

        if (state.busy) {
            player.sendMessage(Component.text("Der Greifarm ist gerade unterwegs.", NamedTextColor.YELLOW));
            return;
        }
        if (state.moving) {
            return;
        }

        if (state.ownerId == null) {
            hand.subtract(ENTRY_COST);
            state.ownerId = player.getUniqueId();
            manager.setStatusText(machine.baseLocation(), "Pfeile steuern, Haken gratis");
            clicked.getWorld().playSound(clicked.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.BLOCKS, 0.75f, 1.2f);
            player.sendMessage(Component.text("Greifarm aktiviert. Bewege ihn mit den Pfeilen.", NamedTextColor.GREEN));
            return;
        }
    }

    private void handleStartedInteraction(Player player, GrabberManager.GrabberMachine machine, GrabberState state,
            GrabberManager.Control control) {
        if (state.busy) {
            player.sendMessage(Component.text("Der Greifarm ist gerade unterwegs.", NamedTextColor.YELLOW));
            return;
        }
        if (state.moving) {
            return;
        }
        if (!state.ownerId.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Dieser Greifarm gehoert gerade einem anderen Spieler.", NamedTextColor.YELLOW));
            return;
        }
        if (control == null) {
            return;
        }

        if (control == GrabberManager.Control.DROP) {
            runGrab(player, machine, state);
            return;
        }

        moveControl(machine, state, control);
    }

    private void moveControl(GrabberManager.GrabberMachine machine, GrabberState state, GrabberManager.Control control) {
        int oldCol = state.col;
        int oldRow = state.row;
        switch (control) {
            case LEFT -> state.col = Math.max(0, state.col - 1);
            case RIGHT -> state.col = Math.min(8, state.col + 1);
            case UP -> state.row = Math.min(8, state.row + 1);
            case DOWN -> state.row = Math.max(0, state.row - 1);
            case DROP -> {
                return;
            }
        }

        int targetCol = state.col;
        int targetRow = state.row;
        state.moving = true;
        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (!manager.isMachine(machine.baseLocation())) {
                    state.moving = false;
                    cancel();
                    return;
                }

                step++;
                double progress = Math.min(1.0, step / (double) MOVE_STEPS);
                double currentCol = oldCol + ((targetCol - oldCol) * progress);
                double currentRow = oldRow + ((targetRow - oldRow) * progress);
                manager.updateClaw(machine.baseLocation(), currentCol, currentRow, 0.0);

                if (step < MOVE_STEPS) {
                    return;
                }

                state.moving = false;
                manager.updateClaw(machine.baseLocation(), targetCol, targetRow, 0.0);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
        machine.baseLocation().getWorld().playSound(machine.baseLocation(), Sound.BLOCK_PISTON_EXTEND, SoundCategory.BLOCKS, 0.45f, 1.35f);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        GrabberManager.GrabberMachine machine = manager.findMachineByBlock(event.getBlock());
        if (machine == null) {
            return;
        }

        clearState(machine.baseLocation());
        manager.remove(machine.baseLocation(), true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (GrabberManager.GrabberMachine machine : manager.getMachinesInChunk(event.getWorld(), chunk.getX(), chunk.getZ())) {
            manager.spawnDisplays(machine.baseLocation());
            syncState(machine.baseLocation());
        }
    }

    private void runGrab(Player player, GrabberManager.GrabberMachine machine, GrabberState state) {
        Location base = machine.baseLocation();
        state.busy = true;
        manager.setStatusText(base, "Greift...");
        base.getWorld().playSound(base, Sound.BLOCK_CHAIN_HIT, SoundCategory.BLOCKS, 0.8f, 0.9f);

        new BukkitRunnable() {
            private int step;
            private boolean success;
            private boolean resolved;
            private int caughtSlot = -1;
            private ItemStack reward;

            @Override
            public void run() {
                if (!manager.isMachine(base)) {
                    manager.removeCarriedItem(base);
                    cancel();
                    return;
                }

                step++;
                int resolveStep = DROP_STEPS / 3;
                double half = DROP_STEPS / 2.0;
                double depth;
                if (step <= resolveStep) {
                    depth = (SUCCESS_GRAB_DEPTH / resolveStep) * step;
                } else if (resolved && success) {
                    double retractProgress = Math.min(1.0, (step - resolveStep) / (double) (DROP_STEPS - resolveStep));
                    depth = Math.max(0.0, SUCCESS_GRAB_DEPTH * (1.0 - retractProgress));
                } else if (step <= half) {
                    double deeperProgress = (step - resolveStep) / (half - resolveStep);
                    depth = SUCCESS_GRAB_DEPTH + ((MAX_DEPTH - SUCCESS_GRAB_DEPTH) * deeperProgress);
                } else {
                    depth = Math.max(0.0, MAX_DEPTH - ((step - half) * (MAX_DEPTH / half)));
                }
                manager.updateClaw(base, state.col, state.row, depth);
                if (success) {
                    manager.teleportCarriedItem(base, manager.getCarryLocation(base, state.col, state.row, depth));
                }

                if (!resolved && step == resolveStep) {
                    resolved = true;
                    caughtSlot = findReachablePrizeSlot(base, state);
                    if (caughtSlot >= 0) {
                        reward = state.prizes.get(caughtSlot);
                    }
                    success = caughtSlot >= 0
                            && reward != null
                            && reward.getType() != Material.AIR
                            && ThreadLocalRandom.current().nextDouble() < 0.38;
                    if (success) {
                        manager.spawnCarriedItem(base, reward, manager.getPrizeCarryLocation(base, caughtSlot));
                        manager.setPrizeItem(base, caughtSlot, new ItemStack(Material.AIR), 0.08, 0.0f, 0.0f, 0.0f);
                        base.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, base.clone().add(0.5, 1.15, 0.5), 8, 0.22, 0.12, 0.22, 0.01);
                        base.getWorld().playSound(base, Sound.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.8f, 1.2f);
                    } else {
                        player.playSound(base, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.8f, 0.7f);
                        player.playSound(base, Sound.BLOCK_CHAIN_BREAK, SoundCategory.PLAYERS, 0.7f, 0.8f);
                        base.getWorld().playSound(base, Sound.BLOCK_CHAIN_BREAK, SoundCategory.BLOCKS, 0.7f, 0.8f);
                    }
                }

                if (step < DROP_STEPS) {
                    return;
                }

                cancel();
                state.ownerId = null;

                if (success) {
                    animateWinToChute(player, base, reward, state);
                    return;
                }

                manager.removeCarriedItem(base);
                state.busy = false;
                manager.setStatusText(base, "Daneben");
                returnToStart(base, state);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private int findReachablePrizeSlot(Location base, GrabberState state) {
        Location clawLocation = manager.getCarryLocation(base, state.col, state.row, SUCCESS_GRAB_DEPTH);
        double maxDistanceSquared = 0.18 * 0.18;
        int bestSlot = -1;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (int slot = 0; slot < state.prizes.size(); slot++) {
            ItemStack prize = state.prizes.get(slot);
            if (prize == null || prize.getType() == Material.AIR) {
                continue;
            }

            Location prizeLocation = manager.getPrizeCarryLocation(base, slot);
            double dx = prizeLocation.getX() - clawLocation.getX();
            double dz = prizeLocation.getZ() - clawLocation.getZ();
            double distanceSquared = (dx * dx) + (dz * dz);
            if (distanceSquared > maxDistanceSquared || distanceSquared >= bestDistanceSquared) {
                continue;
            }

            bestSlot = slot;
            bestDistanceSquared = distanceSquared;
        }

        return bestSlot;
    }

    private GrabberState getOrCreateState(Location base) {
        GrabberState state = states.get(serializeKey(base));
        if (state != null) {
            return state;
        }
        return createState(base);
    }

    private GrabberState createState(Location base) {
        List<ItemStack> prizes = new ArrayList<>(GrabberManager.PRIZE_DISPLAY_COUNT);
        for (int i = 0; i < GrabberManager.PRIZE_DISPLAY_COUNT; i++) {
            prizes.add(randomPrize());
        }
        GrabberState state = new GrabberState(prizes);
        states.put(serializeKey(base), state);
        syncState(base);
        return state;
    }

    private void syncState(Location base) {
        GrabberState state = states.get(serializeKey(base));
        if (state == null) {
            state = createState(base);
            return;
        }

        syncPrizeDisplays(base, state);
        manager.updateClaw(base, state.col, state.row, 0.0);
        manager.setStatusText(base, buildStatusText(state));
    }

    private void restoreStatusLater(Location base) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (manager.isMachine(base)) {
                GrabberState state = states.get(serializeKey(base));
                if (state != null) {
                    manager.setStatusText(base, buildStatusText(state));
                }
            }
        }, 30L);
    }

    private void clearState(Location base) {
        states.remove(serializeKey(base));
    }

    private void syncPrizeDisplays(Location base, GrabberState state) {
        for (int slot = 0; slot < state.prizes.size(); slot++) {
            manager.setPrizeItem(
                    base,
                    slot,
                    state.prizes.get(slot),
                    ThreadLocalRandom.current().nextDouble(-0.03, 0.03),
                    ThreadLocalRandom.current().nextFloat(-180.0f, 180.0f),
                    ThreadLocalRandom.current().nextFloat(-35.0f, 35.0f),
                    ThreadLocalRandom.current().nextFloat(-35.0f, 35.0f));
        }
    }

    private void shufflePrizes(GrabberState state) {
        Collections.shuffle(state.prizes);
        int refillIndex = ThreadLocalRandom.current().nextInt(state.prizes.size());
        state.prizes.set(refillIndex, randomPrize());
    }

    private void animateWinToChute(Player player, Location base, ItemStack reward, GrabberState state) {
        manager.setStatusText(base, "Ausgabe...");
        int oldCol = state.col;
        int oldRow = state.row;
        state.col = 0;
        state.row = 0;

        new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                if (!manager.isMachine(base)) {
                    manager.removeCarriedItem(base);
                    cancel();
                    return;
                }

                tick++;
                double progress = Math.min(1.0, tick / 28.0);
                double currentCol = oldCol + ((0 - oldCol) * progress);
                double currentRow = oldRow + ((0 - oldRow) * progress);
                manager.updateClaw(base, currentCol, currentRow, 0.0);
                manager.teleportCarriedItem(base, manager.getCarryLocation(base, currentCol, currentRow, 0.0));

                if (progress < 1.0) {
                    return;
                }

                cancel();
                dropRewardAtFront(player, base, reward, state);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void dropRewardAtFront(Player player, Location base, ItemStack reward, GrabberState state) {
        Location start = manager.getCarryLocation(base, 0.0, 0.0, 0.0);
        Location end = manager.getFrontDropLocation(base);
        player.getInventory().addItem(reward.clone());
        player.sendMessage(Component.text("Greifer Erfolg: " + formatReward(reward), NamedTextColor.GOLD));
        manager.setStatusText(base, "Gewonnen");

        new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                if (!manager.isMachine(base)) {
                    manager.removeCarriedItem(base);
                    manager.removeFloorReward(base);
                    cancel();
                    return;
                }

                tick++;
                double progress = Math.min(1.0, tick / 10.0);
                Location current = start.clone().add(
                        (end.getX() - start.getX()) * progress,
                        (end.getY() - start.getY()) * progress,
                        (end.getZ() - start.getZ()) * progress);
                manager.teleportCarriedItem(base, current);

                if (progress < 1.0) {
                    return;
                }

                cancel();
                manager.removeCarriedItem(base);
                manager.spawnFloorReward(base, reward, end);
                player.playSound(base, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.9f, 1.05f);
                player.playSound(base, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8f, 1.15f);
                player.playSound(base, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 1.0f);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    manager.removeFloorReward(base);
                    shufflePrizes(state);
                    syncPrizeDisplays(base, state);
                    state.busy = false;
                    returnToStart(base, state);
                }, 60L);
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void returnToStart(Location base, GrabberState state) {
        int oldCol = state.col;
        int oldRow = state.row;
        state.col = 0;
        state.row = 0;
        state.moving = true;

        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (!manager.isMachine(base)) {
                    state.moving = false;
                    cancel();
                    return;
                }

                step++;
                double progress = Math.min(1.0, step / (double) MOVE_STEPS);
                double currentCol = oldCol + ((0 - oldCol) * progress);
                double currentRow = oldRow + ((0 - oldRow) * progress);
                manager.updateClaw(base, currentCol, currentRow, 0.0);

                if (step < MOVE_STEPS) {
                    return;
                }

                state.moving = false;
                manager.updateClaw(base, 0.0, 0.0, 0.0);
                restoreStatusLater(base);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private ItemStack randomPrize() {
        return PRIZE_POOL.get(ThreadLocalRandom.current().nextInt(PRIZE_POOL.size())).clone();
    }

    private String formatReward(ItemStack reward) {
        return reward.getAmount() + "x " + reward.getType().name();
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":"
                + location.getBlockZ();
    }

    private String buildStatusText(GrabberState state) {
        if (state.busy) {
            return "Greift...";
        }
        if (state.moving) {
            return "Bewegt...";
        }
        if (state.ownerId == null) {
            return "Start: 1 Emerald";
        }
        return "Pfeile steuern, Haken gratis";
    }

    private void openInfoBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.title(Component.text("Greifarm"));
        meta.author(Component.text("Casino"));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.pages(
                Component.text("Greifarm", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text("Bau:", NamedTextColor.DARK_GREEN))
                        .append(Component.newline())
                        .append(Component.text("Chiseled Bookshelf unten.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Direkt darueber ein Glasblock.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Am Glas ein ItemFrame mit Chain.", NamedTextColor.BLACK)),
                Component.text("Steuerung", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text("Die 2x3 Regal-Slots sind die Steuerung.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Mit 1 Emerald am Regal aktivierst du erst deine Runde.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Oben: links, oben, rechts. Unten Mitte: runter. Unten rechts: greifen.", NamedTextColor.BLACK)),
                Component.text("Spiel", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text("1 Emerald startet genau eine Runde.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Sobald du den Haken ausloest, endet die Runde danach wieder.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Gewinne laufen erst zum schwarzen Ausgabeschacht links vorne.", NamedTextColor.BLACK)));
        book.setItemMeta(meta);
        player.openBook(book);
    }

    private static final class GrabberState {
        private final List<ItemStack> prizes;
        private int col = 0;
        private int row = 0;
        private boolean busy;
        private boolean moving;
        private UUID ownerId;

        private GrabberState(List<ItemStack> prizes) {
            this.prizes = prizes;
        }
    }
}
