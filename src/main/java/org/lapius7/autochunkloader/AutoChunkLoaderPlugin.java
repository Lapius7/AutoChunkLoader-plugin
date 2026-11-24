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

public final class AutoChunkLoaderPlugin extends JavaPlugin implements Listener {
    private Messages messages;
    private GroupManager groups;
    private Inspector inspector;
    private AclCommand command;

    public Messages messages() { return messages; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load(getConfig().getString("language", "ja"));

        groups = new GroupManager(this);
        groups.load();
        groups.applyAll();

        inspector = new Inspector(this, groups);
        inspector.start();

        command = new AclCommand(this, groups, inspector);
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
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) { groups.applyWorld(e.getWorld()); }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) { groups.releaseWorld(e.getWorld().getName()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        inspector.remove(e.getPlayer().getUniqueId());
        command.forget(e.getPlayer().getUniqueId());
    }
}
