package mcbesser.casino;

import java.util.HashMap;
import java.util.Map;
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
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CoinFlipListener implements Listener {

    private static final float SNOW_SCALE = 0.28f;
    private static final float FIRE_SCALE = 0.24f;
    private static final double COIN_CENTER_X = 0.492;
    private static final double COIN_CENTER_Z = 0.499;
    private final JavaPlugin plugin;
    private final CoinFlipManager manager;
    private final Map<String, StreakState> streaks = new HashMap<>();
    private final Map<String, Boolean> activeFlips = new HashMap<>();
    private final Map<String, BukkitRunnable> countdowns = new HashMap<>();
    private final Map<String, BukkitRunnable> pendingResets = new HashMap<>();

    public CoinFlipListener(JavaPlugin plugin, CoinFlipManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onItemFrameInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }

        ItemStack handItem = event.getPlayer().getInventory().getItemInMainHand();
        if (handItem.getType() != Material.SNOWBALL) {
            return;
        }

        Block attached = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
        if (attached.getType() != Material.JUKEBOX || manager.isGame(attached.getLocation())) {
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (attached.getType() == Material.JUKEBOX) {
                frame.setVisible(false);
                frame.setFixed(true);
                manager.register(attached);
                frame.setItem(null, false);
                attached.getWorld().playSound(attached.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.BLOCKS, 0.6f, 1.2f);
            }
        });
    }

    @EventHandler
    public void onJukeboxInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.JUKEBOX || !manager.isGame(clicked.getLocation())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();

        String key = serializeKey(clicked.getLocation());
        StreakState state = streaks.get(key);
        if (state != null && !state.playerId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Dieser Coinflip laeuft gerade fuer einen anderen Spieler.", NamedTextColor.YELLOW));
            return;
        }

        int streak = state != null ? state.streak() : 0;
        int pendingPayout = state != null ? state.pendingPayout() : 0;
        int cost = streak + 1;

        if (hand.getType() != Material.EMERALD || hand.getAmount() < cost) {
            openInfoBook(player);
            return;
        }

        if (Boolean.TRUE.equals(activeFlips.get(key))) {
            player.sendMessage(Component.text("Der Coinflip laeuft bereits.", NamedTextColor.YELLOW));
            return;
        }

        startFlip(player, clicked, hand, cost, streak, pendingPayout);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.JUKEBOX && manager.isGame(event.getBlock().getLocation())) {
            String key = serializeKey(event.getBlock().getLocation());
            activeFlips.remove(key);
            streaks.remove(key);
            cancelCountdown(key);
            cancelPendingReset(key);
            manager.remove(event.getBlock().getLocation(), true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (CoinFlipManager.CoinFlipInstance instance : manager.getGamesInChunk(event.getWorld(), chunk.getX(), chunk.getZ())) {
            manager.spawnDisplays(instance.jukeboxLocation());
        }
    }

    private void startFlip(Player player, Block jukebox, ItemStack hand, int cost, int currentStreak, int currentPendingPayout) {
        Location location = jukebox.getLocation();
        String key = serializeKey(location);
        activeFlips.put(key, true);
        cancelCountdown(key);
        cancelPendingReset(key);
        hand.subtract(cost);

        ItemDisplay snow = manager.getItemDisplay(location, "snowball_static");
        ItemDisplay fire = manager.getItemDisplay(location, "fire_static");
        ItemDisplay coinSnow = manager.getItemDisplay(location, "coin_flip_snowball");
        ItemDisplay coinFire = manager.getItemDisplay(location, "coin_flip_fire");
        if (snow != null) {
            snow.setVisibleByDefault(false);
        }
        if (fire != null) {
            fire.setVisibleByDefault(false);
        }
        if (coinSnow != null) {
            coinSnow.setVisibleByDefault(true);
        }
        if (coinFire != null) {
            coinFire.setVisibleByDefault(true);
        }

        boolean snowballWins = ThreadLocalRandom.current().nextBoolean();
        int payout = (currentStreak + 1) * 2;

        new BukkitRunnable() {
            private int tick;
            private static final int TOTAL_TICKS = 32;
            private static final long RESULT_HOLD_TICKS = 40L;

            @Override
            public void run() {
                if (!jukebox.getChunk().isLoaded() || !manager.isGame(location)) {
                    cleanup(false);
                    return;
                }

                tick++;
                float progress = tick / (float) TOTAL_TICKS;
                double arcHeight = Math.sin(progress * Math.PI) * 0.68;
                double y = 1.08 + arcHeight;
                float finalAngle = snowballWins ? 0.0f : 180.0f;
                float spinAngle = progress * 1080.0f;

                if (coinSnow != null && coinSnow.isValid()) {
                    coinSnow.teleport(location.clone().add(COIN_CENTER_X, y, COIN_CENTER_Z));
                    coinSnow.setTransformation(createFlipTransformation(SNOW_SCALE, spinAngle, false));
                }
                if (coinFire != null && coinFire.isValid()) {
                    coinFire.teleport(location.clone().add(COIN_CENTER_X - 0.004, y - 0.012, COIN_CENTER_Z + 0.003));
                    coinFire.setTransformation(createFlipTransformation(FIRE_SCALE, spinAngle, true));
                }

                if (tick % 3 == 0) {
                    jukebox.getWorld().playSound(location, Sound.ITEM_TRIDENT_RETURN, SoundCategory.BLOCKS, 0.18f, 1.05f);
                }

                if (tick >= TOTAL_TICKS) {
                    setResultLayout(location, coinSnow, coinFire, snowballWins);
                    if (snowballWins) {
                        resolveResult(player, location, key, true, payout, currentStreak, currentPendingPayout);
                    } else {
                        resolveResult(player, location, key, false, payout, currentStreak, currentPendingPayout);
                        BukkitRunnable pendingReset = new BukkitRunnable() {
                            @Override
                            public void run() {
                                pendingResets.remove(key);
                                if (!manager.isGame(location)) {
                                    return;
                                }
                                manager.resetTable(location);
                            }
                        };
                        pendingResets.put(key, pendingReset);
                        pendingReset.runTaskLater(plugin, RESULT_HOLD_TICKS);
                    }
                    cleanup(true);
                }
            }

            private void cleanup(boolean finished) {
                cancel();
                activeFlips.remove(key);
                if (!finished) {
                    manager.resetTable(location);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void resolveResult(Player player, Location location, String key, boolean snowballWins, int payout, int currentStreak, int currentPendingPayout) {
        if (!manager.isGame(location)) {
            return;
        }

        if (snowballWins) {
            int nextPayout = currentPendingPayout > 0 ? currentPendingPayout * 2 : payout;
            player.sendMessage(Component.text("Gewonnen! Pot: " + nextPayout + " Emerald. Nochmal innerhalb von 4 Sekunden.", NamedTextColor.GOLD));
            location.getWorld().playSound(location, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.BLOCKS, 0.8f, 1.2f);
            streaks.put(key, new StreakState(player.getUniqueId(), currentStreak + 1, nextPayout));
            startCountdown(location, key);
            return;
        }

        streaks.remove(key);
        player.sendMessage(Component.text("Verloren. Fire Charge oben.", NamedTextColor.RED));
        location.getWorld().playSound(location, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.8f, 0.9f);
    }

    private void startCountdown(Location location, String key) {
        manager.setTimerLights(location, 3);
        cancelCountdown(key);
        BukkitRunnable countdown = new BukkitRunnable() {
            private int second = 4;

            @Override
            public void run() {
                if (!manager.isGame(location)) {
                    streaks.remove(key);
                    countdowns.remove(key);
                    cancel();
                    return;
                }

                second--;
                manager.setTimerLights(location, Math.max(0, second - 1));
                if (second <= 0) {
                    payoutAndReset(location, key);
                    streaks.remove(key);
                    countdowns.remove(key);
                    cancel();
                }
            }
        };
        countdowns.put(key, countdown);
        countdown.runTaskTimer(plugin, 20L, 20L);
    }

    private void openInfoBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) {
            return;
        }

        meta.title(Component.text("CoinFlip"));
        meta.author(Component.text("Casino"));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.pages(
            Component.text("CoinFlip", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Aktivierung:", NamedTextColor.DARK_GREEN))
                .append(Component.newline())
                .append(Component.text("Jukebox + ItemFrame + Snowball.", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Danach siehst du Snowball und Fire Charge auf dem Tisch.", NamedTextColor.BLACK)),
            Component.text("Spiel", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Klick mit Emerald in der Hand.", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Snowball oben = Gewinn.", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Fire Charge oben = Verlust.", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Gewinne werden erst nach 4 Sekunden ausgezahlt.", NamedTextColor.BLACK)),
            Component.text("Streak", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text("Einsatz = aktueller Streak + 1 Emerald", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Erster Sieg = 2 Emerald Pot", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Jeder weitere Sieg verdoppelt den Pot.", NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text("Verlust vor Cashout = alles weg.", NamedTextColor.BLACK))
        );
        book.setItemMeta(meta);
        player.openBook(book);
    }

    private void setResultLayout(Location location, ItemDisplay coinSnow, ItemDisplay coinFire, boolean snowballWins) {
        float snowRoll = snowballWins ? -90.0f : 90.0f;
        float fireRoll = snowballWins ? 90.0f : -90.0f;
        double topY = 1.053;
        double bottomY = 1.041;
        if (coinSnow != null && coinSnow.isValid()) {
            coinSnow.setVisibleByDefault(true);
            coinSnow.teleport(location.clone().add(COIN_CENTER_X, snowballWins ? topY : bottomY, COIN_CENTER_Z));
            coinSnow.setTransformation(createResultTransformation(SNOW_SCALE, snowRoll, false));
        }
        if (coinFire != null && coinFire.isValid()) {
            coinFire.setVisibleByDefault(true);
            coinFire.teleport(location.clone().add(COIN_CENTER_X, snowballWins ? bottomY : topY, COIN_CENTER_Z));
            coinFire.setTransformation(createResultTransformation(FIRE_SCALE, fireRoll, true));
        }
    }

    private void payoutAndReset(Location location, String key) {
        StreakState state = streaks.get(key);
        if (state != null) {
            Player owner = plugin.getServer().getPlayer(state.playerId());
            if (owner != null && owner.isOnline()) {
                owner.getInventory().addItem(new ItemStack(Material.EMERALD, state.pendingPayout()));
                owner.sendMessage(Component.text("Cashout! +" + state.pendingPayout() + " Emerald.", NamedTextColor.GREEN));
            } else if (location.getWorld() != null) {
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 1.1, 0.5), new ItemStack(Material.EMERALD, state.pendingPayout()));
            }
        }
        manager.resetTable(location);
    }

    private void cancelCountdown(String key) {
        BukkitRunnable existing = countdowns.remove(key);
        if (existing != null) {
            existing.cancel();
        }
    }

    private void cancelPendingReset(String key) {
        BukkitRunnable existing = pendingResets.remove(key);
        if (existing != null) {
            existing.cancel();
        }
    }

    private Transformation createResultTransformation(float scale, float rollDegrees, boolean backSide) {
        float sideOffset = backSide ? 180.0f : 0.0f;
        return new Transformation(
            new Vector3f(0.0f, 0.0f, 0.0f),
            new Quaternionf()
                .rotateY((float) Math.toRadians(90.0f))
                .rotateX((float) Math.toRadians(90.0f))
                .rotateZ((float) Math.toRadians(rollDegrees))
                .rotateX((float) Math.toRadians(sideOffset)),
            new Vector3f(scale, scale, scale),
            new Quaternionf()
        );
    }

    private String serializeKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private Transformation createFlipTransformation(float scale, float flipAngle, boolean backSide) {
        float sideOffset = backSide ? 180.0f : 0.0f;
        return new Transformation(
            new Vector3f(0.0f, 0.0f, 0.0f),
            new Quaternionf()
                .rotateY((float) Math.toRadians(90.0f))
                .rotateX((float) Math.toRadians(flipAngle + sideOffset)),
            new Vector3f(scale, scale, scale),
            new Quaternionf()
        );
    }

    private record StreakState(java.util.UUID playerId, int streak, int pendingPayout) {
    }
}
