package org.lapius7.autochunkloader.inspector;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.lapius7.autochunkloader.AutoChunkLoaderPlugin;
import org.lapius7.autochunkloader.loader.GroupManager;

import java.util.*;

/** Draws the outer outline of all kept chunks with particles, sent only to players who toggled it on. */
public final class Inspector {
    /** A horizontal outline segment in block coordinates (axis-aligned). */
    private record Seg(double x1, double z1, double x2, double z2) { }

    private final AutoChunkLoaderPlugin plugin;
    private final GroupManager mgr;
    private final Set<UUID> enabled = new HashSet<>();
    private final Map<String, List<Seg>> cache = new HashMap<>();
    private int cacheVersion = -1;
    private BukkitTask task;

    public Inspector(AutoChunkLoaderPlugin plugin, GroupManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    public boolean enabled(Player p) { return enabled.contains(p.getUniqueId()); }

    public void set(Player p, boolean on) {
        if (on) enabled.add(p.getUniqueId()); else enabled.remove(p.getUniqueId());
    }

    public void remove(UUID u) { enabled.remove(u); }

    public void start() {
        stop();
        int interval = Math.max(1, plugin.getConfig().getInt("inspector.interval-ticks", 5));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    public void shutdown() {
        stop();
        enabled.clear();
        cache.clear();
    }

    // ------------------------------------------------------------------
    private List<Seg> outline(String world) {
        if (cacheVersion != mgr.version()) { cache.clear(); cacheVersion = mgr.version(); }
        return cache.computeIfAbsent(world, w -> {
            Set<Long> set = mgr.ticketedChunks(w);
            List<Seg> out = new ArrayList<>();
            for (long k : set) {
                int x = GroupManager.unpackX(k), z = GroupManager.unpackZ(k);
                double x0 = x * 16.0, z0 = z * 16.0, x1 = x0 + 16, z1 = z0 + 16;
                if (!set.contains(GroupManager.pack(x - 1, z))) out.add(new Seg(x0, z0, x0, z1));
                if (!set.contains(GroupManager.pack(x + 1, z))) out.add(new Seg(x1, z0, x1, z1));
                if (!set.contains(GroupManager.pack(x, z - 1))) out.add(new Seg(x0, z0, x1, z0));
                if (!set.contains(GroupManager.pack(x, z + 1))) out.add(new Seg(x0, z1, x1, z1));
            }
            return out;
        });
    }

    private void tick() {
        if (enabled.isEmpty()) return;
        FileConfiguration c = plugin.getConfig();
        double radiusBlocks = Math.max(1, c.getInt("inspector.radius", 6)) * 16.0;
        double height = Math.max(1, c.getDouble("inspector.height", 8));
        double step = Math.max(0.25, c.getDouble("inspector.step", 1.0));
        int max = Math.max(50, c.getInt("inspector.max-particles", 600));
        Color color = parseColor(c.getString("inspector.color", "#4ade80"));
        float size = (float) c.getDouble("inspector.size", 1.0);

        for (Iterator<UUID> it = enabled.iterator(); it.hasNext(); ) {
            Player p = plugin.getServer().getPlayer(it.next());
            if (p == null) { it.remove(); continue; }
            World w = p.getWorld();
            List<Seg> segs = outline(w.getName());
            if (segs.isEmpty()) continue;
            Location eye = p.getEyeLocation();
            double ex = eye.getX(), ez = eye.getZ(), ey = eye.getY();
            double yLo = Math.max(w.getMinHeight(), ey - height), yHi = Math.min(w.getMaxHeight(), ey + height);

            List<Seg> near = new ArrayList<>();
            for (Seg s : segs) {
                double mx = (s.x1 + s.x2) / 2, mz = (s.z1 + s.z2) / 2;
                if (Math.abs(mx - ex) <= radiusBlocks && Math.abs(mz - ez) <= radiusBlocks) near.add(s);
            }
            if (near.isEmpty()) continue;

            // Horizontal rings on a world-aligned grid (every 4 blocks) so they do not slide while walking,
            // plus vertical lines at segment ends (one every chunk border).
            List<double[]> pts = new ArrayList<>();
            for (Seg s : near) {
                double len = Math.abs(s.x2 - s.x1) + Math.abs(s.z2 - s.z1);
                int n = Math.max(1, (int) Math.round(len / step));
                for (double y = Math.ceil(yLo / 4) * 4; y <= yHi; y += 4)
                    for (int i = 0; i <= n; i++) {
                        double t = (double) i / n;
                        pts.add(new double[]{s.x1 + (s.x2 - s.x1) * t, y, s.z1 + (s.z2 - s.z1) * t});
                    }
                for (double y = yLo; y <= yHi; y += step) {
                    pts.add(new double[]{s.x1, y, s.z1});
                    pts.add(new double[]{s.x2, y, s.z2});
                }
            }
            int stride = Math.max(1, (int) Math.ceil(pts.size() / (double) max));
            var builder = Particle.DUST.builder().receivers(p).color(color, size).count(1).force(true);
            for (int i = 0; i < pts.size(); i += stride) {
                double[] q = pts.get(i);
                builder.location(new Location(w, q[0], q[1], q[2])).spawn();
            }
        }
    }

    private static Color parseColor(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return Color.fromRGB(Integer.parseInt(h, 16));
        } catch (Exception e) {
            return Color.LIME;
        }
    }
}
