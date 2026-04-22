package mcbesser.casino;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class CasinoPlugin extends JavaPlugin {

    private SlotMachineManager slotMachineManager;
    private HorseRaceManager horseRaceManager;
    private CoinFlipManager coinFlipManager;
    private DiceManager diceManager;
    private MemoryManager memoryManager;
    private GrabberManager grabberManager;
    private AttractionStatsManager attractionStatsManager;
    private AttractionSidebarManager attractionSidebarManager;
    private BukkitTask[] displaySyncTasks = new BukkitTask[0];

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
        diceManager = new DiceManager(this);
        diceManager.load();
        diceManager.spawnAllDisplays();
        memoryManager = new MemoryManager(this);
        memoryManager.load();
        memoryManager.spawnAllDisplays();
        grabberManager = new GrabberManager(this);
        grabberManager.load();
        grabberManager.spawnAllDisplays();

        getServer().getPluginManager().registerEvents(new SlotMachineListener(this, slotMachineManager, attractionStatsManager), this);
        getServer().getPluginManager().registerEvents(new HorseRaceListener(this, horseRaceManager, attractionStatsManager), this);
        getServer().getPluginManager().registerEvents(new CoinFlipListener(this, coinFlipManager, attractionStatsManager), this);
        getServer().getPluginManager().registerEvents(new DiceListener(this, diceManager), this);
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
        long displaySyncInterval = Math.max(40L, getConfig().getLong("display.sync-interval-ticks", 60L));
        long displaySyncStep = Math.max(1L, displaySyncInterval / 6L);
        long displaySyncStart = 22L;
        displaySyncTasks = new BukkitTask[] {
            getServer().getScheduler().runTaskTimer(this, slotMachineManager::syncHandles, displaySyncStart, displaySyncInterval),
            getServer().getScheduler().runTaskTimer(this, horseRaceManager::syncDisplays, displaySyncStart + displaySyncStep, displaySyncInterval),
            getServer().getScheduler().runTaskTimer(this, coinFlipManager::syncDisplays, displaySyncStart + displaySyncStep * 2L, displaySyncInterval),
            getServer().getScheduler().runTaskTimer(this, diceManager::syncDisplays, displaySyncStart + displaySyncStep * 3L, displaySyncInterval),
            getServer().getScheduler().runTaskTimer(this, memoryManager::syncDisplays, displaySyncStart + displaySyncStep * 4L, displaySyncInterval),
            getServer().getScheduler().runTaskTimer(this, grabberManager::syncDisplays, displaySyncStart + displaySyncStep * 5L, displaySyncInterval)
        };
    }

    @Override
    public void onDisable() {
        for (BukkitTask displaySyncTask : displaySyncTasks) {
            if (displaySyncTask != null) {
                displaySyncTask.cancel();
            }
        }
        displaySyncTasks = new BukkitTask[0];
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
        if (diceManager != null) {
            diceManager.shutdown();
        }
        if (memoryManager != null) {
            memoryManager.shutdown();
        }
        if (grabberManager != null) {
            grabberManager.shutdown();
        }
        CasinoDisplayUtil.clearCache();
        if (attractionStatsManager != null) {
            attractionStatsManager.save();
        }
    }
}
