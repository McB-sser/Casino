package mcbesser.casino;

public enum AttractionType {
    SLOT_MACHINE("SlotMachine", "Beste Bilanz", RankingMetric.NET_PROFIT),
    HORSE_RACE("Pferderennen", "Meiste Siege", RankingMetric.WINS),
    COIN_FLIP("CoinFlip", "Beste Serie", RankingMetric.BEST_STREAK),
    MEMORY("Memory", "Meiste Siege", RankingMetric.WINS),
    GRABBER("Greifarm", "Meiste Funde", RankingMetric.WINS);

    private final String displayName;
    private final String rankingLabel;
    private final RankingMetric rankingMetric;

    AttractionType(String displayName, String rankingLabel, RankingMetric rankingMetric) {
        this.displayName = displayName;
        this.rankingLabel = rankingLabel;
        this.rankingMetric = rankingMetric;
    }

    public String displayName() {
        return displayName;
    }

    public String rankingLabel() {
        return rankingLabel;
    }

    public RankingMetric rankingMetric() {
        return rankingMetric;
    }

    public enum RankingMetric {
        WINS,
        BEST_STREAK,
        BEST_PAYOUT,
        NET_PROFIT
    }
}
