package mcbesser.casino;

import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
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
import org.bukkit.util.Vector;

public final class HorseRaceListener implements Listener {

    private static final int ENTRY_COST = 1;
    private static final int WIN_PAYOUT = 4;
    private static final int RACE_STEP_TICKS = 2;
    private static final int MIN_RACE_TICKS = 20 * 30;
    private static final double MIN_SPEED = 0.0025;
    private static final double MAX_SPEED = 0.0040;

    private final JavaPlugin plugin;
    private final HorseRaceManager manager;
    private final AttractionStatsManager statsManager;

    public HorseRaceListener(JavaPlugin plugin, HorseRaceManager manager, AttractionStatsManager statsManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.statsManager = statsManager;
    }

    @EventHandler
    public void onItemFrameInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }

        ItemStack handItem = event.getPlayer().getInventory().getItemInMainHand();
        if (!isHorseArmor(handItem.getType())) {
            return;
        }

        Block attached = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
        if (attached.getType() != Material.LODESTONE || manager.isRace(attached.getLocation())) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (attached.getType() == Material.LODESTONE) {
                frame.setVisible(false);
                frame.setFixed(true);
                manager.registerRace(attached);
                attached.getWorld().playSound(attached.getLocation(), Sound.ENTITY_HORSE_AMBIENT, SoundCategory.BLOCKS, 0.7f, 1.0f);
            }
        });
    }

    @EventHandler
    public void onLodestoneInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.LODESTONE || !manager.isRace(clicked.getLocation())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand.getType() != Material.EMERALD || hand.getAmount() < ENTRY_COST) {
            openInfoBook(player);
            return;
        }

        startRace(player, clicked, hand);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.LODESTONE && manager.isRace(event.getBlock().getLocation())) {
            manager.removeRace(event.getBlock().getLocation(), true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (HorseRaceManager.HorseRaceInstance instance : manager.getRacesInChunk(event.getWorld(), chunk.getX(), chunk.getZ())) {
            manager.spawnRaceDisplays(instance.lodestoneLocation());
        }
    }

    private void startRace(Player player, Block lodestone, ItemStack hand) {
        Location location = lodestone.getLocation();
        if (!manager.beginRace(location)) {
            player.sendMessage(Component.text("Das Rennen laeuft bereits.", NamedTextColor.YELLOW));
            return;
        }

        hand.subtract(ENTRY_COST);
        statsManager.recordPlay(AttractionType.HORSE_RACE, player.getUniqueId());
        int chosenRacer = ThreadLocalRandom.current().nextInt(HorseRaceManager.RACER_ITEMS.size());
        player.sendMessage(Component.text("Dein Pferd: " + getRacerName(chosenRacer), NamedTextColor.AQUA));

        double[] speeds = new double[HorseRaceManager.RACER_ITEMS.size()];
        double[] progress = new double[HorseRaceManager.RACER_ITEMS.size()];
        for (int i = 0; i < speeds.length; i++) {
            speeds[i] = 0.0030 + ThreadLocalRandom.current().nextDouble() * 0.0004;
            progress[i] = 0.0;
        }

        ItemFrame frame = manager.findAttachedFrame(location);
        ItemStack previousFrameItem = frame != null && frame.getItem() != null ? frame.getItem().clone() : null;
        if (frame != null) {
            frame.setItem(HorseRaceManager.RACER_ITEMS.get(chosenRacer).clone(), false);
        }

        new BukkitRunnable() {
            private int tick;
            private int finishPause;
            private Integer winner;
            private boolean raceRemoved;

            @Override
            public void run() {
                if (!lodestone.getChunk().isLoaded() || !manager.isRace(location)) {
                    raceRemoved = !manager.isRace(location);
                    cleanup();
                    return;
                }

                if (winner != null) {
                    finishPause += RACE_STEP_TICKS;
                    if (finishPause >= 40) {
                        cleanup();
                    }
                    return;
                }

                tick++;
                for (int i = 0; i < progress.length; i++) {
                    speeds[i] = clampSpeed(speeds[i] + ThreadLocalRandom.current().nextDouble(-0.00018, 0.00018));
                    progress[i] += speeds[i];
                    ItemDisplay display = manager.getRacerDisplay(location, i);
                    if (display != null && display.isValid()) {
                        display.teleport(manager.getRacerLocation(location, i, progress[i]));
                        display.setTransformation(new org.bukkit.util.Transformation(
                            new org.joml.Vector3f(0.0f, 0.0f, 0.0f),
                            manager.getRacerRotation(progress[i]),
                            new org.joml.Vector3f(0.35f, 0.35f, 0.35f),
                            new org.joml.Quaternionf()
                        ));
                    }
                }

                lodestone.getWorld().playSound(location, Sound.ENTITY_HORSE_GALLOP, SoundCategory.BLOCKS, 0.7f, 1.0f + (tick % 3) * 0.05f);

                int raceWinner = getFinishedRacer(progress, tick * RACE_STEP_TICKS);
                if (raceWinner != -1) {
                    winner = raceWinner;
                    if (winner == chosenRacer) {
                        statsManager.recordWin(AttractionType.HORSE_RACE, player.getUniqueId(), WIN_PAYOUT);
                        player.getInventory().addItem(new ItemStack(Material.EMERALD, WIN_PAYOUT));
                        player.sendMessage(Component.text("Dein Pferd gewinnt! +" + WIN_PAYOUT + " Emerald", NamedTextColor.GOLD));
                        playWinBurst(location);
                        playWinnerSound(location);
                    } else {
                        statsManager.recordLoss(AttractionType.HORSE_RACE, player.getUniqueId());
                        player.sendMessage(Component.text("Gewonnen hat " + getRacerName(winner) + ".", NamedTextColor.GRAY));
                        playLoserFinishSound(location);
                    }
                }
            }

            private void cleanup() {
                cancel();
                if (frame != null && frame.isValid()) {
                    frame.setItem(new ItemStack(Material.LEATHER_HORSE_ARMOR), false);
                }
                if (!raceRemoved) {
                    manager.endRace(location);
                    manager.spawnRaceDisplays(location);
                }
            }
        }.runTaskTimer(plugin, 0L, RACE_STEP_TICKS);
    }

    private int getFinishedRacer(double[] progress, int elapsedTicks) {
        if (elapsedTicks < MIN_RACE_TICKS) {
            return -1;
        }

        int winner = -1;
        double best = 1.0;
        for (int i = 0; i < progress.length; i++) {
            if (progress[i] >= 1.0 && (winner == -1 || progress[i] > best)) {
                winner = i;
                best = progress[i];
            }
        }
        return winner;
    }

    private double clampSpeed(double speed) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    private void playWinBurst(Location location) {
        if (location.getWorld() == null) {
            return;
        }

        location.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, location.clone().add(0.5, 1.0, 0.5), 14, 0.3, 0.2, 0.3, 0.02);
        for (int i = 0; i < 4; i++) {
            org.bukkit.entity.Item emerald = location.getWorld().dropItem(location.clone().add(0.5, 0.6, 0.5), new ItemStack(Material.EMERALD));
            emerald.setPickupDelay(40);
            emerald.setCanPlayerPickup(false);
            emerald.setVelocity(new Vector(
                (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.18,
                0.18 + ThreadLocalRandom.current().nextDouble() * 0.08,
                (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.18
            ));
            plugin.getServer().getScheduler().runTaskLater(plugin, emerald::remove, 20L);
        }
    }

    private void playWinnerSound(Location location) {
        if (location.getWorld() == null) {
            return;
        }

        location.getWorld().playSound(location, Sound.ENTITY_HORSE_AMBIENT, SoundCategory.BLOCKS, 1.0f, 1.25f);
        location.getWorld().playSound(location, Sound.ENTITY_HORSE_GALLOP, SoundCategory.BLOCKS, 0.9f, 1.15f);
        location.getWorld().playSound(location, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.BLOCKS, 0.8f, 1.1f);
    }

    private void playLoserFinishSound(Location location) {
        if (location.getWorld() == null) {
            return;
        }

        location.getWorld().playSound(location, Sound.ENTITY_HORSE_BREATHE, SoundCategory.BLOCKS, 0.7f, 0.9f);
    }

    private boolean isHorseArmor(Material material) {
        return material == Material.LEATHER_HORSE_ARMOR
            || material == Material.IRON_HORSE_ARMOR
            || material == Material.GOLDEN_HORSE_ARMOR
            || material == Material.DIAMOND_HORSE_ARMOR;
    }

    private String getRacerName(int racerIndex) {
        return switch (racerIndex) {
            case 0 -> "Eisenpferd";
            case 1 -> "Goldpferd";
            case 2 -> "Kupferpferd";
            case 3 -> "Netheritpferd";
            default -> "Rennpferd";
        };
    }


    private void openInfoBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.title(Component.text("Pferderennen"));
        meta.author(Component.text("Casino"));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.pages(
            Component.text("Pferderennen", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Aktivierung:", NamedTextColor.DARK_GREEN))
                .append(Component.newline())
                .append(Component.text("ItemFrame mit Pferderuestung am Lodestone.", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Danach laufen 4 Mini-Racer im Kreis.", NamedTextColor.BLACK)),
            Component.text("Teilnahme", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Rechtsklick auf den Lodestone.", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Du brauchst 1 Emerald in der Hand.", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Du bekommst zufaellig einen Startplatz.", NamedTextColor.BLACK)),
            Component.text("Gewinn", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Gewinnt dein Pferd:", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("+4 Emerald", NamedTextColor.DARK_GREEN, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Einsatz: 1 Emerald", NamedTextColor.DARK_GREEN))
        );
        book.setItemMeta(meta);
        player.openBook(book);
    }
}
