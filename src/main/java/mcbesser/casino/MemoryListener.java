package mcbesser.casino;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.entity.ItemDisplay;
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
import org.jetbrains.annotations.Nullable;

public final class MemoryListener implements Listener {

    private static final int TOTAL_SLOTS = 54;
    private static final long MISMATCH_DELAY_TICKS = 24L;
    private static final long RESET_DELAY_TICKS = 40L;
    private static final int START_ATTEMPTS = 40;
    private static final int START_COST = 1;
    private static final int START_REWARD = 27;
    private static final int PENALTY_INTERVAL_SECONDS = 10;
    private static final List<Material> CARD_POOL = buildCardPool();

    private final JavaPlugin plugin;
    private final MemoryManager manager;
    private final AttractionStatsManager statsManager;
    private final Map<String, MemoryState> states = new HashMap<>();
    private final Map<String, BukkitRunnable> pendingMismatch = new HashMap<>();
    private final Map<String, BukkitRunnable> pendingReset = new HashMap<>();
    private final Map<String, BukkitRunnable> countdowns = new HashMap<>();
    private final Map<String, BukkitRunnable> statusRestores = new HashMap<>();

    public MemoryListener(JavaPlugin plugin, MemoryManager manager, AttractionStatsManager statsManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.statsManager = statsManager;
    }

    @EventHandler
    public void onActivatorPlaced(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }

        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (hand.getType() != Material.EGG) {
            return;
        }

        Block attached = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
        if (attached.getType() != Material.CHISELED_BOOKSHELF || manager.isBoard(attached.getLocation())) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (attached.getType() != Material.CHISELED_BOOKSHELF) {
                return;
            }
            if (!isEggActivator(frame)) {
                return;
            }
            if (!manager.register(attached, frame)) {
                return;
            }

            createFreshState(attached.getLocation());
            attached.getWorld().playSound(attached.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.BLOCKS, 0.75f, 1.15f);
        });
    }

    @EventHandler
    public void onBoardClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.CHISELED_BOOKSHELF) {
            return;
        }

        MemoryManager.MemoryBoard board = manager.findBoardContainingBlock(clicked);
        if (board == null) {
            return;
        }

        event.setCancelled(true);
        String key = serializeKey(board.centerLocation());
        MemoryState state = states.get(key);
        if (state == null) {
            state = createFreshState(board.centerLocation());
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (!state.started()) {
            if (hand.getType() == Material.EMERALD && hand.getAmount() >= START_COST) {
                hand.subtract(START_COST);
                statsManager.recordPlay(AttractionType.MEMORY, player.getUniqueId());
                state.started(true);
                state.ownerId(player.getUniqueId());
                manager.setStatusText(board.centerLocation(), buildStatusText(state));
                startCountdown(board.centerLocation(), state);
                clicked.getWorld().playSound(clicked.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.BLOCKS, 0.75f, 1.25f);
                player.sendMessage(Component.text("Memory gestartet. Drehe jetzt Karten um.", NamedTextColor.GREEN));
                return;
            }

            openInfoBook(player);
            return;
        }

        if (!(clicked.getState() instanceof ChiseledBookshelf shelf)) {
            return;
        }

        if (event.getClickedPosition() == null) {
            return;
        }

        int localSlot = shelf.getSlot(event.getClickedPosition());
        int globalSlot = manager.getGlobalSlot(board.centerLocation(), clicked, localSlot);
        if (localSlot < 0 || globalSlot < 0) {
            return;
        }
        handleSlotClick(player, board.centerLocation(), globalSlot);
    }

    @EventHandler
    public void onSlotDisplayClick(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ItemDisplay display)) {
            return;
        }
        if (!manager.isSlotDisplay(display)) {
            return;
        }

        event.setCancelled(true);
        MemoryManager.MemoryBoard board = manager.getBoardForDisplay(display);
        Integer slotIndex = manager.getSlotIndex(display);
        if (board == null || slotIndex == null) {
            return;
        }

        handleSlotClick(event.getPlayer(), board.centerLocation(), slotIndex);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        MemoryManager.MemoryBoard board = manager.findBoardContainingBlock(event.getBlock());
        if (board == null) {
            return;
        }

        clearState(board.centerLocation());
        manager.remove(board.centerLocation(), true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (MemoryManager.MemoryBoard board : manager.getBoardsInChunk(event.getWorld(), chunk.getX(), chunk.getZ())) {
            manager.spawnDisplays(board.centerLocation());
            syncBoard(board.centerLocation());
        }
    }

    private void handleSlotClick(Player player, Location center, int slotIndex) {
        String key = serializeKey(center);
        MemoryState state = states.get(key);
        if (state == null) {
            state = createFreshState(center);
        }
        if (state.locked() || state.finished() || state.attemptsLeft() <= 0
                || state.matched().contains(slotIndex) || state.revealed().contains(slotIndex)) {
            return;
        }
        if (!state.started()) {
            player.sendMessage(Component.text("Starte das Spiel erst mit 1 Emerald am Regal.", NamedTextColor.YELLOW));
            return;
        }
        if (state.ownerId() != null && !state.ownerId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Dieses Memory-Spiel gehoert gerade einem anderen Spieler.", NamedTextColor.YELLOW));
            return;
        }

        Material card = state.cards().get(slotIndex);
        manager.setSlotItem(center, slotIndex, card);
        state.revealed().add(slotIndex);
        center.getWorld().playSound(center, Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.BLOCKS, 0.55f, 1.2f);

        if (state.revealed().size() == 1) {
            return;
        }

        if (state.revealed().size() < 2) {
            return;
        }

        int first = state.revealed().get(0);
        int second = state.revealed().get(1);
        Material firstCard = state.cards().get(first);
        Material secondCard = state.cards().get(second);

        if (firstCard == secondCard) {
            state.matched().add(first);
            state.matched().add(second);
            state.revealed().clear();
            state.pairsFound(state.pairsFound() + 1);
            state.secondsUntilPenalty(state.secondsUntilPenalty() + 5);
            manager.setStatusText(center, buildStatusText(state));
            center.getWorld().playSound(center, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.75f, 1.35f);
            center.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.BLOCKS, 0.65f, 1.4f);
            center.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(0.5, 1.0, 0.7), 12, 0.35, 0.35, 0.2, 0.01);
            pulseStatus(center, "§a+5s");

            if (state.matched().size() == TOTAL_SLOTS) {
                center.getWorld().playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.BLOCKS, 0.75f, 1.0f);
                finishRound(center, state, true);
            }
            return;
        }

        state.attemptsLeft(state.attemptsLeft() - 1);
        manager.setStatusText(center, buildStatusText(state));
        state.locked(true);
        center.getWorld().playSound(center, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.BLOCKS, 0.7f, 0.7f);

        BukkitRunnable existing = pendingMismatch.remove(key);
        if (existing != null) {
            existing.cancel();
        }

        MemoryState currentState = state;
        BukkitRunnable mismatchReset = new BukkitRunnable() {
            @Override
            public void run() {
                pendingMismatch.remove(key);
                if (!manager.isBoard(center)) {
                    return;
                }
                manager.setSlotItem(center, first, Material.EGG);
                manager.setSlotItem(center, second, Material.EGG);
                currentState.revealed().clear();
                currentState.locked(false);
                if (currentState.attemptsLeft() <= 0) {
                    finishRound(center, currentState, false);
                }
            }
        };
        pendingMismatch.put(key, mismatchReset);
        mismatchReset.runTaskLater(plugin, MISMATCH_DELAY_TICKS);
    }

    private MemoryState createFreshState(Location center) {
        List<Material> cards = new ArrayList<>(CARD_POOL.subList(0, TOTAL_SLOTS / 2));
        List<Material> deck = new ArrayList<>(TOTAL_SLOTS);
        for (Material material : cards) {
            deck.add(material);
            deck.add(material);
        }
        Collections.shuffle(deck);

        cancelReset(serializeKey(center));
        cancelCountdown(serializeKey(center));
        MemoryState state = new MemoryState(deck, new ArrayList<>(), new HashSet<>(), false, null, START_ATTEMPTS, 0,
                false, false, START_REWARD, PENALTY_INTERVAL_SECONDS);
        states.put(serializeKey(center), state);
        manager.resetAllSlots(center);
        manager.setStatusText(center, buildStatusText(state));
        return state;
    }

    private void syncBoard(Location center) {
        MemoryState state = states.get(serializeKey(center));
        if (state == null) {
            manager.resetAllSlots(center);
            manager.setStatusText(center, "");
            return;
        }

        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            if (state.matched().contains(slot) || state.revealed().contains(slot)) {
                manager.setSlotItem(center, slot, state.cards().get(slot));
            } else {
                manager.setSlotItem(center, slot, Material.EGG);
            }
        }
        manager.setStatusText(center, buildStatusText(state));
    }

    private void clearState(Location center) {
        String key = serializeKey(center);
        states.remove(key);
        BukkitRunnable mismatch = pendingMismatch.remove(key);
        if (mismatch != null) {
            mismatch.cancel();
        }
        cancelReset(key);
        cancelCountdown(key);
        cancelStatusRestore(key);
    }

    private void finishRound(Location center, MemoryState state, boolean won) {
        if (state.finished()) {
            return;
        }
        state.finished(true);
        state.locked(true);
        cancelCountdown(serializeKey(center));
        if (won && state.rewardLeft() > 0) {
            Player owner = state.ownerId() == null ? null : plugin.getServer().getPlayer(state.ownerId());
            if (state.ownerId() != null) {
                statsManager.recordWin(AttractionType.MEMORY, state.ownerId(), state.rewardLeft());
            }
            if (owner != null && owner.isOnline()) {
                owner.getInventory().addItem(new ItemStack(Material.EMERALD, state.rewardLeft()));
                owner.sendMessage(Component.text("Memory geloest: +" + state.rewardLeft() + " Emerald", NamedTextColor.GOLD));
            } else if (center.getWorld() != null) {
                center.getWorld().dropItemNaturally(center.clone().add(0.5, 0.7, 0.5), new ItemStack(Material.EMERALD, state.rewardLeft()));
            }
            center.getWorld().playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.BLOCKS, 0.85f, 1.0f);
            manager.setStatusText(center, "Gewinn: " + state.rewardLeft());
        } else {
            if (state.ownerId() != null) {
                statsManager.recordLoss(AttractionType.MEMORY, state.ownerId());
            }
            center.getWorld().playSound(center, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.85f, 0.8f);
            manager.setStatusText(center, "Verloren");
        }

        String key = serializeKey(center);
        BukkitRunnable resetTask = new BukkitRunnable() {
            @Override
            public void run() {
                pendingReset.remove(key);
                if (!manager.isBoard(center)) {
                    return;
                }
                createFreshState(center);
            }
        };
        pendingReset.put(key, resetTask);
        resetTask.runTaskLater(plugin, RESET_DELAY_TICKS);
    }

    private void cancelReset(String key) {
        BukkitRunnable existing = pendingReset.remove(key);
        if (existing != null) {
            existing.cancel();
        }
    }

    private void startCountdown(Location center, MemoryState state) {
        String key = serializeKey(center);
        cancelCountdown(key);
        BukkitRunnable countdown = new BukkitRunnable() {
            @Override
            public void run() {
                if (!manager.isBoard(center) || state.finished() || !state.started()) {
                    countdowns.remove(key);
                    cancel();
                    return;
                }

                state.secondsUntilPenalty(state.secondsUntilPenalty() - 1);
                if (state.secondsUntilPenalty() <= 0) {
                    state.secondsUntilPenalty(PENALTY_INTERVAL_SECONDS);
                    state.rewardLeft(Math.max(0, state.rewardLeft() - 1));
                    center.getWorld().playSound(center, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.55f, 0.55f);
                    center.getWorld().spawnParticle(Particle.ENCHANT, center.clone().add(0.5, 0.8, 0.7), 10, 0.28, 0.28, 0.15, 0.03);
                    pulseStatus(center, "§c-1 Emerald");
                    if (state.rewardLeft() <= 0) {
                        manager.setStatusText(center, buildStatusText(state));
                        finishRound(center, state, false);
                        countdowns.remove(key);
                        cancel();
                        return;
                    }
                }

                manager.setStatusText(center, buildStatusText(state));
            }
        };
        countdowns.put(key, countdown);
        countdown.runTaskTimer(plugin, 20L, 20L);
    }

    private void cancelCountdown(String key) {
        BukkitRunnable existing = countdowns.remove(key);
        if (existing != null) {
            existing.cancel();
        }
    }

    private void pulseStatus(Location center, String text) {
        String key = serializeKey(center);
        cancelStatusRestore(key);
        manager.setStatusText(center, text);

        BukkitRunnable restore = new BukkitRunnable() {
            @Override
            public void run() {
                statusRestores.remove(key);
                MemoryState state = states.get(key);
                if (state == null || state.finished() || !manager.isBoard(center)) {
                    return;
                }
                manager.setStatusText(center, buildStatusText(state));
            }
        };
        statusRestores.put(key, restore);
        restore.runTaskLater(plugin, 20L);
    }

    private void cancelStatusRestore(String key) {
        BukkitRunnable existing = statusRestores.remove(key);
        if (existing != null) {
            existing.cancel();
        }
    }

    private String buildStatusText(MemoryState state) {
        if (!state.started()) {
            return "";
        }
        return "✖" + state.attemptsLeft() + " ♦" + state.rewardLeft() + " ⏱" + state.secondsUntilPenalty();
    }

    private void openInfoBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.title(Component.text("Memory"));
        meta.author(Component.text("Casino"));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.pages(
                Component.text("Memory", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text("Bau:", NamedTextColor.DARK_GREEN))
                        .append(Component.newline())
                        .append(Component.text("3x3 Chiseled Bookshelf.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Im mittleren Regal ein ItemFrame mit Egg.", NamedTextColor.BLACK)),
                Component.text("Spielziel", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text("Vor jedem Regal-Slot liegt ein Egg.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Starte am Regal mit 1 Emerald.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Jedes Spawn Egg kommt genau zweimal vor.", NamedTextColor.BLACK)),
                Component.text("Ablauf", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text("40 Fehlversuche pro Runde.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Danach klickst du die echten Regal-Slots.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Startgewinn 27 Emerald.", NamedTextColor.BLACK))
                        .append(Component.newline())
                        .append(Component.text("Alle 10 Sekunden sinkt der Gewinn um 1.", NamedTextColor.BLACK)));
        book.setItemMeta(meta);
        player.openBook(book);
    }

    private boolean isEggActivator(ItemFrame frame) {
        return frame.getItem() != null && frame.getItem().getType() == Material.EGG;
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":"
                + location.getBlockZ();
    }

    private static List<Material> buildCardPool() {
        List<Material> materials = new ArrayList<>();
        Set<Material> excluded = EnumSet.of(Material.EGG);
        for (Material material : Material.values()) {
            if (!material.name().endsWith("_SPAWN_EGG")) {
                continue;
            }
            if (excluded.contains(material)) {
                continue;
            }
            materials.add(material);
        }
        Collections.shuffle(materials);
        return materials;
    }

    private static final class MemoryState {
        private final List<Material> cards;
        private final List<Integer> revealed;
        private final Set<Integer> matched;
        private boolean locked;
        private @Nullable UUID ownerId;
        private int attemptsLeft;
        private int pairsFound;
        private boolean finished;
        private boolean started;
        private int rewardLeft;
        private int secondsUntilPenalty;

        private MemoryState(List<Material> cards, List<Integer> revealed, Set<Integer> matched, boolean locked,
                @Nullable UUID ownerId, int attemptsLeft, int pairsFound, boolean finished, boolean started,
                int rewardLeft, int secondsUntilPenalty) {
            this.cards = cards;
            this.revealed = revealed;
            this.matched = matched;
            this.locked = locked;
            this.ownerId = ownerId;
            this.attemptsLeft = attemptsLeft;
            this.pairsFound = pairsFound;
            this.finished = finished;
            this.started = started;
            this.rewardLeft = rewardLeft;
            this.secondsUntilPenalty = secondsUntilPenalty;
        }

        private List<Material> cards() {
            return cards;
        }

        private List<Integer> revealed() {
            return revealed;
        }

        private Set<Integer> matched() {
            return matched;
        }

        private boolean locked() {
            return locked;
        }

        private void locked(boolean locked) {
            this.locked = locked;
        }

        private @Nullable UUID ownerId() {
            return ownerId;
        }

        private void ownerId(@Nullable UUID ownerId) {
            this.ownerId = ownerId;
        }

        private int attemptsLeft() {
            return attemptsLeft;
        }

        private void attemptsLeft(int attemptsLeft) {
            this.attemptsLeft = attemptsLeft;
        }

        private int pairsFound() {
            return pairsFound;
        }

        private void pairsFound(int pairsFound) {
            this.pairsFound = pairsFound;
        }

        private boolean finished() {
            return finished;
        }

        private void finished(boolean finished) {
            this.finished = finished;
        }

        private boolean started() {
            return started;
        }

        private void started(boolean started) {
            this.started = started;
        }

        private int rewardLeft() {
            return rewardLeft;
        }

        private void rewardLeft(int rewardLeft) {
            this.rewardLeft = rewardLeft;
        }

        private int secondsUntilPenalty() {
            return secondsUntilPenalty;
        }

        private void secondsUntilPenalty(int secondsUntilPenalty) {
            this.secondsUntilPenalty = secondsUntilPenalty;
        }
    }
}

