package mcbesser.casino;

import org.bukkit.plugin.java.JavaPlugin;

public final class CasinoPlugin extends JavaPlugin {

    private SlotMachineManager slotMachineManager;
    private HorseRaceManager horseRaceManager;
    private CoinFlipManager coinFlipManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        slotMachineManager = new SlotMachineManager(this);
        slotMachineManager.load();
        slotMachineManager.spawnAllHandles();
        horseRaceManager = new HorseRaceManager(this);
        horseRaceManager.load();
        horseRaceManager.spawnAllDisplays();
        coinFlipManager = new CoinFlipManager(this);
        coinFlipManager.load();
        coinFlipManager.spawnAllDisplays();

        getServer().getPluginManager().registerEvents(new SlotMachineListener(this, slotMachineManager), this);
        getServer().getPluginManager().registerEvents(new HorseRaceListener(this, horseRaceManager), this);
        getServer().getPluginManager().registerEvents(new CoinFlipListener(this, coinFlipManager), this);
    }

    @Override
    public void onDisable() {
        if (slotMachineManager != null) {
            slotMachineManager.shutdown();
        }
        if (horseRaceManager != null) {
            horseRaceManager.shutdown();
        }
        if (coinFlipManager != null) {
            coinFlipManager.shutdown();
        }
    }
}
