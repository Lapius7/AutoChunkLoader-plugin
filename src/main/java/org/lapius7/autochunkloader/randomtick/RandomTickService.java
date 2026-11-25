package org.lapius7.autochunkloader.randomtick;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.lapius7.autochunkloader.AutoChunkLoaderPlugin;
import org.lapius7.autochunkloader.loader.GroupManager;
import org.lapius7.autochunkloader.model.ChunkGroup;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Switches random ticks off for the chunks of groups with {@code randomtick} off.
 *
 * <p>Paper picks the blocks to random-tick from a section only when {@code LevelChunkSection.tickingBlockCount}
 * (blocks) or {@code tickingFluidCount} (fluids) is above zero. Between the start and the end of a server tick this
 * sets both counters of the affected sections to 0, so those sections get no random tick, and afterwards adds the
 * saved values back. The counters are plain +1/-1 tallies, so block changes made during the tick (which move the
 * zeroed counter by their own delta) are kept: restored value = saved + counter. Nothing else about the chunk
 * changes: it stays loaded and entity-ticking, and entities, block entities (hoppers), scheduled ticks and redstone
 * run as usual. Chunks outside the groups are not touched.
 */
public final class RandomTickService implements Listener {
    private final AutoChunkLoaderPlugin plugin;
    private final GroupManager mgr;
    private VarHandle blocks, fluids;
    private boolean ok;
    private boolean registered;

    /** Sections zeroed for the tick in progress, with the values they had. */
    private final List<LevelChunkSection> held = new ArrayList<>();
    private final List<short[]> saved = new ArrayList<>();

    public RandomTickService(AutoChunkLoaderPlugin plugin, GroupManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    /** false when this server's LevelChunkSection does not match Paper 26.3. */
    public boolean enabled() { return ok; }

    public void start() {
        try {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(LevelChunkSection.class, MethodHandles.lookup());
            blocks = l.findVarHandle(LevelChunkSection.class, "tickingBlockCount", short.class);
            fluids = l.findVarHandle(LevelChunkSection.class, "tickingFluidCount", short.class);
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ok = false;
            plugin.getLogger().severe("Random tick switch disabled: this server's LevelChunkSection does not match Paper 26.3 (" + e + ")");
            return;
        }
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
    }

    public void stop() {
        restore();
        HandlerList.unregisterAll(this);
        registered = false;
    }

    /** First thing in the tick, before any level ticks. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTickStart(ServerTickStartEvent e) {
        restore(); // never leave a section zeroed if an end event was missed
        if (!ok) return;
        Set<String> worlds = new HashSet<>();
        for (ChunkGroup g : mgr.all()) if (g.active() && !g.randomTick()) worlds.add(g.world());
        for (String name : worlds) {
            World w = Bukkit.getWorld(name);
            if (w == null) continue;
            ServerLevel level = ((CraftWorld) w).getHandle();
            // A chunk in several groups: random ticks stop when any of them has them off.
            Set<Long> off = new HashSet<>();
            for (ChunkGroup g : mgr.all()) if (g.active() && !g.randomTick() && g.world().equals(name)) off.addAll(g.chunks());
            for (long k : off) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkGroup.unpackX(k), ChunkGroup.unpackZ(k));
                if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)) continue;
                for (LevelChunkSection s : chunk.getSections()) {
                    short b = (short) blocks.get(s), f = (short) fluids.get(s);
                    if (b == 0 && f == 0) continue;
                    held.add(s);
                    saved.add(new short[]{b, f});
                    blocks.set(s, (short) 0);
                    fluids.set(s, (short) 0);
                }
            }
        }
    }

    /** After every level has ticked. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent e) { restore(); }

    private void restore() {
        for (int i = 0; i < held.size(); i++) {
            LevelChunkSection s = held.get(i);
            short[] v = saved.get(i);
            blocks.set(s, (short) (v[0] + (short) blocks.get(s)));
            fluids.set(s, (short) (v[1] + (short) fluids.get(s)));
        }
        held.clear();
        saved.clear();
    }
}
