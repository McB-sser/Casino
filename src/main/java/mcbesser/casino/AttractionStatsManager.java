package mcbesser.casino;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class AttractionStatsManager {

    private static final String CONFIG_ROOT = "attraction-stats";

    private final JavaPlugin plugin;
    private final Map<AttractionType, Map<UUID, PlayerAttractionStats>> stats = new EnumMap<>(AttractionType.class);

    public AttractionStatsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        for (AttractionType type : AttractionType.values()) {
            stats.put(type, new HashMap<>());
        }
    }

    public void load() {
        for (Map<UUID, PlayerAttractionStats> map : stats.values()) {
            map.clear();
        }

        ConfigurationSection root = plugin.getConfig().getConfigurationSection(CONFIG_ROOT);
        if (root == null) {
            return;
        }

        for (AttractionType type : AttractionType.values()) {
            ConfigurationSection typeSection = root.getConfigurationSection(type.name().toLowerCase());
            if (typeSection == null) {
                continue;
            }

            Map<UUID, PlayerAttractionStats> typeStats = stats.get(type);
            for (String key : typeSection.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(key);
                    ConfigurationSection playerSection = typeSection.getConfigurationSection(key);
                    if (playerSection == null) {
                        continue;
                    }

                    PlayerAttractionStats entry = new PlayerAttractionStats();
                    entry.plays = playerSection.getInt("plays");
                    entry.wins = playerSection.getInt("wins");
                    entry.losses = playerSection.getInt("losses");
                    entry.currentStreak = playerSection.getInt("current-streak");
                    entry.bestStreak = playerSection.getInt("best-streak");
                    entry.totalEmeraldsWon = playerSection.getInt("total-emeralds-won");
                    entry.bestPayout = playerSection.getInt("best-payout");
                    typeStats.put(playerId, entry);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public void save() {
        plugin.getConfig().set(CONFIG_ROOT, null);
        ConfigurationSection root = plugin.getConfig().createSection(CONFIG_ROOT);

        for (AttractionType type : AttractionType.values()) {
            ConfigurationSection typeSection = root.createSection(type.name().toLowerCase());
            for (Map.Entry<UUID, PlayerAttractionStats> entry : stats.get(type).entrySet()) {
                String path = entry.getKey().toString();
                PlayerAttractionStats value = entry.getValue();
                typeSection.set(path + ".plays", value.plays);
                typeSection.set(path + ".wins", value.wins);
                typeSection.set(path + ".losses", value.losses);
                typeSection.set(path + ".current-streak", value.currentStreak);
                typeSection.set(path + ".best-streak", value.bestStreak);
                typeSection.set(path + ".total-emeralds-won", value.totalEmeraldsWon);
                typeSection.set(path + ".best-payout", value.bestPayout);
            }
        }

        plugin.saveConfig();
    }

    public void recordPlay(AttractionType type, UUID playerId) {
        getStats(type, playerId).plays++;
    }

    public void recordWin(AttractionType type, UUID playerId, int emeraldPayout) {
        PlayerAttractionStats stats = getStats(type, playerId);
        stats.wins++;
        stats.currentStreak++;
        stats.bestStreak = Math.max(stats.bestStreak, stats.currentStreak);
        if (emeraldPayout > 0) {
            stats.totalEmeraldsWon += emeraldPayout;
            stats.bestPayout = Math.max(stats.bestPayout, emeraldPayout);
        }
    }

    public void recordLoss(AttractionType type, UUID playerId) {
        PlayerAttractionStats stats = getStats(type, playerId);
        stats.losses++;
        stats.currentStreak = 0;
    }

    public void recordCompletedSeries(AttractionType type, UUID playerId, int streak, int emeraldPayout) {
        PlayerAttractionStats stats = getStats(type, playerId);
        stats.wins++;
        stats.currentStreak = 0;
        stats.bestStreak = Math.max(stats.bestStreak, streak);
        if (emeraldPayout > 0) {
            stats.totalEmeraldsWon += emeraldPayout;
            stats.bestPayout = Math.max(stats.bestPayout, emeraldPayout);
        }
    }

    public void recordPayout(AttractionType type, UUID playerId, int emeraldPayout) {
        if (emeraldPayout <= 0) {
            return;
        }
        PlayerAttractionStats stats = getStats(type, playerId);
        stats.totalEmeraldsWon += emeraldPayout;
        stats.bestPayout = Math.max(stats.bestPayout, emeraldPayout);
    }

    public PlayerStatsView getView(AttractionType type, UUID playerId) {
        PlayerAttractionStats stats = getStats(type, playerId);
        int playedResults = stats.wins + stats.losses;
        int winRate = playedResults <= 0 ? 0 : (int) Math.round((stats.wins * 100.0) / playedResults);
        return new PlayerStatsView(
                stats.plays,
                stats.wins,
                stats.losses,
                stats.currentStreak,
                stats.bestStreak,
                stats.totalEmeraldsWon,
                stats.bestPayout,
                winRate);
    }

    public List<RankingEntry> getTop(AttractionType type, int limit) {
        Comparator<Map.Entry<UUID, PlayerAttractionStats>> comparator = Comparator
                .comparingInt((Map.Entry<UUID, PlayerAttractionStats> entry) -> rankingValue(type, entry.getValue()))
                .reversed()
                .thenComparing(entry -> resolveName(entry.getKey()), String.CASE_INSENSITIVE_ORDER);

        List<RankingEntry> ranking = new ArrayList<>();
        stats.get(type).entrySet().stream()
                .filter(entry -> rankingValue(type, entry.getValue()) > 0)
                .sorted(comparator)
                .limit(limit)
                .forEach(entry -> ranking.add(new RankingEntry(
                        resolveName(entry.getKey()),
                        rankingValue(type, entry.getValue()))));
        return ranking;
    }

    private int rankingValue(AttractionType type, PlayerAttractionStats stats) {
        return switch (type.rankingMetric()) {
            case WINS -> stats.wins;
            case BEST_STREAK -> stats.bestStreak;
            case BEST_PAYOUT -> stats.bestPayout;
            case NET_PROFIT -> stats.totalEmeraldsWon - stats.plays;
        };
    }

    private String resolveName(UUID playerId) {
        OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(playerId);
        String name = offlinePlayer.getName();
        return name == null || name.isBlank() ? playerId.toString().substring(0, 8) : name;
    }

    private PlayerAttractionStats getStats(AttractionType type, UUID playerId) {
        return stats.get(type).computeIfAbsent(playerId, ignored -> new PlayerAttractionStats());
    }

    public record PlayerStatsView(
            int plays,
            int wins,
            int losses,
            int currentStreak,
            int bestStreak,
            int totalEmeraldsWon,
            int bestPayout,
            int winRate) {
    }

    public record RankingEntry(String playerName, int value) {
    }

    private static final class PlayerAttractionStats {
        private int plays;
        private int wins;
        private int losses;
        private int currentStreak;
        private int bestStreak;
        private int totalEmeraldsWon;
        private int bestPayout;
    }
}
