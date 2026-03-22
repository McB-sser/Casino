package mcbesser.casino;

import org.bukkit.plugin.java.JavaPlugin;

public final class CasinoPlugin extends JavaPlugin {

    private SlotMachineManager slotMachineManager;
    private HorseRaceManager horseRaceManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        slotMachineManager = new SlotMachineManager(this);
        slotMachineManager.load();
        slotMachineManager.spawnAllHandles();
        horseRaceManager = new HorseRaceManager(this);
        horseRaceManager.load();
        horseRaceManager.spawnAllDisplays();

        getServer().getPluginManager().registerEvents(new SlotMachineListener(this, slotMachineManager), this);
        getServer().getPluginManager().registerEvents(new HorseRaceListener(this, horseRaceManager), this);
    }

    @Override
    public void onDisable() {
        if (slotMachineManager != null) {
            slotMachineManager.shutdown();
        }
        if (horseRaceManager != null) {
            horseRaceManager.shutdown();
        }
    }
}
