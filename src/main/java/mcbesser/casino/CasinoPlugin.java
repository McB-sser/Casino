package mcbesser.casino;

import org.bukkit.plugin.java.JavaPlugin;

public final class CasinoPlugin extends JavaPlugin {

    private SlotMachineManager slotMachineManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        slotMachineManager = new SlotMachineManager(this);
        slotMachineManager.load();
        slotMachineManager.spawnAllHandles();

        getServer().getPluginManager().registerEvents(new SlotMachineListener(this, slotMachineManager), this);
    }

    @Override
    public void onDisable() {
        if (slotMachineManager != null) {
            slotMachineManager.shutdown();
        }
    }
}
