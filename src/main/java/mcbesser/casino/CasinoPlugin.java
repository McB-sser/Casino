package mcbesser.casino;

import org.bukkit.plugin.java.JavaPlugin;

public final class CasinoPlugin extends JavaPlugin {

    private SlotMachineManager slotMachineManager;
    private HorseRaceManager horseRaceManager;
    private CoinFlipManager coinFlipManager;
    private MemoryManager memoryManager;
    private GrabberManager grabberManager;
    private AttractionStatsManager attractionStatsManager;
    private AttractionSidebarManager attractionSidebarManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        attractionStatsManager = new AttractionStatsManager(this);
        attractionStatsManager.load();
        slotMachineManager = new SlotMachineManager(this);
        slotMachineManager.load();
        slotMachineManager.spawnAllHandles();
        horseRaceManager = new HorseRaceManager(this);
        horseRaceManager.load();
        horseRaceManager.spawnAllDisplays();
        coinFlipManager = new CoinFlipManager(this);
        coinFlipManager.load();
        coinFlipManager.spawnAllDisplays();
        memoryManager = new MemoryManager(this);
        memoryManager.load();
        memoryManager.spawnAllDisplays();
        grabberManager = new GrabberManager(this);
        grabberManager.load();
        grabberManager.spawnAllDisplays();

        getServer().getPluginManager().registerEvents(new SlotMachineListener(this, slotMachineManager, attractionStatsManager), this);
        getServer().getPluginManager().registerEvents(new HorseRaceListener(this, horseRaceManager, attractionStatsManager), this);
        getServer().getPluginManager().registerEvents(new CoinFlipListener(this, coinFlipManager, attractionStatsManager), this);
        getServer().getPluginManager().registerEvents(new MemoryListener(this, memoryManager, attractionStatsManager), this);
        getServer().getPluginManager().registerEvents(new GrabberListener(this, grabberManager, attractionStatsManager), this);

        attractionSidebarManager = new AttractionSidebarManager(
                this,
                attractionStatsManager,
                slotMachineManager,
                horseRaceManager,
                coinFlipManager,
                memoryManager,
                grabberManager);
        getServer().getPluginManager().registerEvents(attractionSidebarManager, this);
        attractionSidebarManager.start();
    }

    @Override
    public void onDisable() {
        if (attractionSidebarManager != null) {
            attractionSidebarManager.shutdown();
        }
        if (slotMachineManager != null) {
            slotMachineManager.shutdown();
        }
        if (horseRaceManager != null) {
            horseRaceManager.shutdown();
        }
        if (coinFlipManager != null) {
            coinFlipManager.shutdown();
        }
        if (memoryManager != null) {
            memoryManager.shutdown();
        }
        if (grabberManager != null) {
            grabberManager.shutdown();
        }
        if (attractionStatsManager != null) {
            attractionStatsManager.save();
        }
    }
}
