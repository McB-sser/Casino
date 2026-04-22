package mcbesser.casino;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public final class AttractionSidebarManager implements Listener {

    private static final String OBJECTIVE_NAME = "casino_attraction";
    private static final int PLAYER_REFRESH_BUDGET = 6;

    private final JavaPlugin plugin;
    private final AttractionStatsManager statsManager;
    private final SlotMachineManager slotMachineManager;
    private final HorseRaceManager horseRaceManager;
    private final CoinFlipManager coinFlipManager;
    private final MemoryManager memoryManager;
    private final GrabberManager grabberManager;
    private final Map<UUID, Scoreboard> previousScoreboards = new HashMap<>();
    private final Map<UUID, AttractionType> activeTypes = new HashMap<>();
    private BukkitTask task;
    private int refreshCursor;

    public AttractionSidebarManager(
            JavaPlugin plugin,
            AttractionStatsManager statsManager,
            SlotMachineManager slotMachineManager,
            HorseRaceManager horseRaceManager,
            CoinFlipManager coinFlipManager,
            MemoryManager memoryManager,
            GrabberManager grabberManager) {
        this.plugin = plugin;
        this.statsManager = statsManager;
        this.slotMachineManager = slotMachineManager;
        this.horseRaceManager = horseRaceManager;
        this.coinFlipManager = coinFlipManager;
        this.memoryManager = memoryManager;
        this.grabberManager = grabberManager;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        refreshCursor = 0;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickViewers, 4L, 10L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            restore(player);
        }
        previousScoreboards.clear();
        activeTypes.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, this::tickViewers, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restore(event.getPlayer());
        previousScoreboards.remove(event.getPlayer().getUniqueId());
        activeTypes.remove(event.getPlayer().getUniqueId());
    }

    private void tickViewers() {
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        if (players.isEmpty()) {
            refreshCursor = 0;
            return;
        }
        if (refreshCursor >= players.size()) {
            refreshCursor = 0;
        }
        int count = Math.min(players.size(), PLAYER_REFRESH_BUDGET);
        for (int i = 0; i < count; i++) {
            Player player = players.get((refreshCursor + i) % players.size());
            AttractionType type = detectLookedAttraction(player);
            if (type == null) {
                restore(player);
                continue;
            }
            show(player, type);
        }
        refreshCursor = (refreshCursor + count) % players.size();
    }

    private AttractionType detectLookedAttraction(Player player) {
        Block target = player.getTargetBlockExact(6, FluidCollisionMode.NEVER);
        if (target == null) {
            return null;
        }

        if (target.getType() == slotMachineManager.getShelfMaterial()
                && slotMachineManager.isMachine(target.getRelative(org.bukkit.block.BlockFace.DOWN).getLocation())) {
            return AttractionType.SLOT_MACHINE;
        }
        if (slotMachineManager.isMachine(target.getLocation())) {
            return AttractionType.SLOT_MACHINE;
        }
        if (horseRaceManager.isRace(target.getLocation())) {
            return AttractionType.HORSE_RACE;
        }
        if (coinFlipManager.isGame(target.getLocation())) {
            return AttractionType.COIN_FLIP;
        }
        if (grabberManager.findMachineByBlock(target) != null) {
            return AttractionType.GRABBER;
        }
        if (memoryManager.findBoardContainingBlock(target) != null) {
            return AttractionType.MEMORY;
        }
        return null;
    }

    private void show(Player player, AttractionType type) {
        UUID playerId = player.getUniqueId();
        if (!previousScoreboards.containsKey(playerId)) {
            previousScoreboards.put(playerId, player.getScoreboard());
        }

        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(
                OBJECTIVE_NAME,
                "dummy",
                ChatColor.GOLD.toString() + ChatColor.BOLD + type.displayName());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = buildLines(type, player);
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(makeUnique(line, score)).setScore(score);
            score--;
        }

        player.setScoreboard(scoreboard);
        activeTypes.put(playerId, type);
    }

    private List<String> buildLines(AttractionType type, Player player) {
        AttractionStatsManager.PlayerStatsView stats = statsManager.getView(type, player.getUniqueId());
        List<AttractionStatsManager.RankingEntry> ranking = statsManager.getTop(type, 3);

        return switch (type) {
            case SLOT_MACHINE -> List.of(
                    ChatColor.DARK_GRAY + " ",
                    ChatColor.YELLOW + "Einsatz: " + ChatColor.WHITE + "1 Emerald",
                    ChatColor.YELLOW + "Jackpot: " + ChatColor.WHITE + "32 Emerald",
                    ChatColor.YELLOW + "Bilanz: " + ChatColor.WHITE + (stats.totalEmeraldsWon() - stats.plays()) + "E",
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + "Deine Werte",
                    label("Spiele") + value(stats.plays()),
                    label("Siege") + value(stats.wins()),
                    label("Bestspin") + value(stats.bestPayout() + "E"),
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + type.rankingLabel(),
                    rankingLine(ranking, 0, "E"),
                    rankingLine(ranking, 1, "E"),
                    rankingLine(ranking, 2, "E"));
            case HORSE_RACE -> List.of(
                    ChatColor.DARK_GRAY + " ",
                    ChatColor.YELLOW + "Einsatz: " + ChatColor.WHITE + "1 Emerald",
                    ChatColor.YELLOW + "Sieg: " + ChatColor.WHITE + "4 Emerald",
                    ChatColor.YELLOW + "Serie: " + ChatColor.WHITE + stats.currentStreak(),
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + "Deine Werte",
                    label("Rennen") + value(stats.plays()),
                    label("Siege") + value(stats.wins()),
                    label("Beste Serie") + value(stats.bestStreak()),
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + type.rankingLabel(),
                    rankingLine(ranking, 0, ""),
                    rankingLine(ranking, 1, ""),
                    rankingLine(ranking, 2, ""));
            case COIN_FLIP -> List.of(
                    ChatColor.DARK_GRAY + " ",
                    ChatColor.YELLOW + "Einsatz: " + ChatColor.WHITE + "1 Emerald",
                    ChatColor.YELLOW + "Cashout: " + ChatColor.WHITE + "nach 4 Sek.",
                    ChatColor.YELLOW + "Beste Serie: " + ChatColor.WHITE + stats.bestStreak(),
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + "Deine Werte",
                    label("Flips") + value(stats.plays()),
                    label("Siege") + value(stats.wins()),
                    label("Bester Cashout") + value(stats.bestPayout() + "E"),
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + type.rankingLabel(),
                    rankingLine(ranking, 0, ""),
                    rankingLine(ranking, 1, ""),
                    rankingLine(ranking, 2, ""));
            case MEMORY -> List.of(
                    ChatColor.DARK_GRAY + " ",
                    ChatColor.YELLOW + "Start: " + ChatColor.WHITE + "1 Emerald",
                    ChatColor.YELLOW + "Max Gewinn: " + ChatColor.WHITE + "27 Emerald",
                    ChatColor.YELLOW + "Abzug: " + ChatColor.WHITE + "-1E / 10s",
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + "Deine Werte",
                    label("Partien") + value(stats.plays()),
                    label("Gel\u00f6st") + value(stats.wins()),
                    label("Bester Gewinn") + value(stats.bestPayout() + "E"),
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + type.rankingLabel(),
                    rankingLine(ranking, 0, ""),
                    rankingLine(ranking, 1, ""),
                    rankingLine(ranking, 2, ""));
            case GRABBER -> List.of(
                    ChatColor.DARK_GRAY + " ",
                    ChatColor.YELLOW + "Start: " + ChatColor.WHITE + "1 Emerald",
                    ChatColor.YELLOW + "Steuerung: " + ChatColor.WHITE + "Regal-Slots",
                    ChatColor.YELLOW + "Treffer: " + ChatColor.WHITE + stats.wins(),
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + "Deine Werte",
                    label("Runden") + value(stats.plays()),
                    label("Funde") + value(stats.wins()),
                    label("Serie") + value(stats.bestStreak()),
                    ChatColor.GRAY + " ",
                    ChatColor.GOLD + type.rankingLabel(),
                    rankingLine(ranking, 0, ""),
                    rankingLine(ranking, 1, ""),
                    rankingLine(ranking, 2, ""));
        };
    }

    private String rankingLine(List<AttractionStatsManager.RankingEntry> ranking, int index, String suffix) {
        if (index >= ranking.size()) {
            return ChatColor.GRAY.toString() + (index + 1) + ". ---";
        }

        AttractionStatsManager.RankingEntry entry = ranking.get(index);
        return ChatColor.WHITE + "" + (index + 1) + ". " + ChatColor.GREEN + entry.playerName()
                + ChatColor.WHITE + ": " + entry.value() + suffix;
    }

    private String label(String text) {
        return ChatColor.GREEN + text + ChatColor.WHITE + ": ";
    }

    private String value(Object value) {
        return ChatColor.WHITE + String.valueOf(value);
    }

    private String makeUnique(String line, int score) {
        return line + ChatColor.COLOR_CHAR + Integer.toHexString(score);
    }

    private void restore(Player player) {
        UUID playerId = player.getUniqueId();
        if (!activeTypes.containsKey(playerId)) {
            return;
        }

        Scoreboard previous = previousScoreboards.remove(playerId);
        activeTypes.remove(playerId);
        player.setScoreboard(previous != null ? previous : Bukkit.getScoreboardManager().getMainScoreboard());
    }
}
