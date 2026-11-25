package org.lapius7.autochunkloader;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.lapius7.autochunkloader.command.AclCommand;
import org.lapius7.autochunkloader.inspector.Inspector;
import org.lapius7.autochunkloader.lang.Messages;
import org.lapius7.autochunkloader.loader.GroupManager;
import org.lapius7.autochunkloader.spawn.SpawnService;
import org.lapius7.autochunkloader.undo.UndoService;
import org.lapius7.autochunkloader.randomtick.RandomTickService;

public final class AutoChunkLoaderPlugin extends JavaPlugin implements Listener {
    private Messages messages;
    private GroupManager groups;
    private Inspector inspector;
    private static final String SUPPORTED_MC = "26.3";
    private SpawnService spawner;
    private RandomTickService randomTicks;
    private AclCommand command;
    private UndoService undo;

    public Messages messages() { return messages; }

    @Override
    public void onEnable() {
        // Decide before any NMS class is touched: this plugin only supports Minecraft 26.3.
        String mc = org.bukkit.Bukkit.getMinecraftVersion();
        if (!SUPPORTED_MC.equals(mc)) {
            getLogger().severe("AutoChunkLoader supports Minecraft " + SUPPORTED_MC + " only, but this server runs " + mc
                    + ". Disabling the plugin; nothing was loaded.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load(getConfig().getString("language", "ja"));

        groups = new GroupManager(this);
        groups.load();
        groups.applyAll();

        inspector = new Inspector(this, groups);
        inspector.load();
        inspector.start();

        spawner = new SpawnService(this, groups);
        spawner.start();
        randomTicks = new RandomTickService(this, groups);
        randomTicks.start();
        undo = new UndoService(this, groups);
        command = new AclCommand(this, groups, inspector, spawner, randomTicks, undo);
        PluginCommand pc = getCommand("acl");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Keeping " + groups.totalChunks() + " chunk(s) in " + groups.all().size() + " group(s) loaded.");
    }

    @Override
    public void onDisable() {
        if (inspector != null) inspector.shutdown();
        if (spawner != null) spawner.stop();
        if (randomTicks != null) randomTicks.stop();
        if (groups != null) {
            groups.releaseAll();
            groups.save();
        }
    }

    /** /acl reload: config + language. Data is not re-read. */
    public void reloadAll() {
        reloadConfig();
        messages.load(getConfig().getString("language", "ja"));
        inspector.start();
        spawner.start();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) { groups.applyWorld(e.getWorld()); }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) { groups.releaseWorld(e.getWorld().getName()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        command.forget(e.getPlayer().getUniqueId());
        undo.forget(e.getPlayer().getUniqueId());
    }
}
