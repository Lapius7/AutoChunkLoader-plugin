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
        double height = Math.max(1, c.getDouble("inspector.height", 16));
        double step = Math.max(0.25, c.getDouble("inspector.step", 0.5));
        double ring = Math.max(1, c.getDouble("inspector.ring-spacing", 2));
        double post = Math.max(2, c.getDouble("inspector.post-spacing", 8));
        double falloff = Math.max(8, c.getDouble("inspector.falloff", 24));
        int max = Math.max(50, c.getInt("inspector.max-particles", 1500));
        Color edgeColor = parseColor(c.getString("inspector.color", "#ffa200"));
        Color cornerColor = parseColor(c.getString("inspector.corner-color", "#ff1f6d"));
        float size = clampSize(c.getDouble("inspector.size", 1.6));
        float cornerSize = clampSize(c.getDouble("inspector.corner-size", 2.4));

        for (Iterator<UUID> it = enabled.iterator(); it.hasNext(); ) {
            Player p = plugin.getServer().getPlayer(it.next());
            if (p == null) { it.remove(); continue; }
            World w = p.getWorld();
            List<Seg> segs = outline(w.getName());
            if (segs.isEmpty()) continue;
            Location eye = p.getEyeLocation();
            double ex = eye.getX(), ez = eye.getZ(), ey = eye.getY();
            double yLo = Math.max(w.getMinHeight(), ey - height), yHi = Math.min(w.getMaxHeight(), ey + height);

            // points: {x, y, z, corner(1)/edge(0)}
            List<double[]> pts = new ArrayList<>();
            Set<Long> corners = new HashSet<>();
            for (Seg s : segs) {
                double mx = (s.x1 + s.x2) / 2, mz = (s.z1 + s.z2) / 2;
                double dist = Math.hypot(mx - ex, mz - ez);
                if (Math.abs(mx - ex) > radiusBlocks || Math.abs(mz - ez) > radiusBlocks) continue;
                // near = dense, far = sparse (spacing grows with distance)
                double k = Math.min(4.0, 1.0 + Math.max(0, dist - 16) / falloff);
                double len = Math.abs(s.x2 - s.x1) + Math.abs(s.z2 - s.z1);
                int n = Math.max(1, (int) Math.round(len / (step * k)));
                for (double y = Math.ceil(yLo / ring) * ring; y <= yHi; y += ring * k)
                    for (int i = 0; i <= n; i++) {
                        double t = (double) i / n;
                        pts.add(new double[]{s.x1 + (s.x2 - s.x1) * t, y, s.z1 + (s.z2 - s.z1) * t, 0});
                    }
                // vertical posts along the edge (without the corners)
                int posts = Math.max(1, (int) Math.round(len / post));
                for (int i = 1; i < posts; i++) {
                    double t = (double) i / posts;
                    double px = s.x1 + (s.x2 - s.x1) * t, pz = s.z1 + (s.z2 - s.z1) * t;
                    for (double y = yLo; y <= yHi; y += step * k) pts.add(new double[]{px, y, pz, 0});
                }
                corners.add(((long) (int) s.x1 << 32) | ((int) s.z1 & 0xFFFFFFFFL));
                corners.add(((long) (int) s.x2 << 32) | ((int) s.z2 & 0xFFFFFFFFL));
            }
            for (long q : corners) {
                double cx = (int) (q >> 32), cz = (int) q;
                double k = Math.min(4.0, 1.0 + Math.max(0, Math.hypot(cx - ex, cz - ez) - 16) / falloff);
                for (double y = yLo; y <= yHi; y += step * k) pts.add(new double[]{cx, y, cz, 1});
            }
            if (pts.isEmpty()) continue;

            long nCorner = pts.stream().filter(q -> q[3] == 1).count();
            int stride = Math.max(1, (int) Math.ceil((pts.size() - nCorner) / (double) Math.max(50, max - nCorner)));
            var edge = Particle.DUST.builder().receivers(p).color(edgeColor, size).count(1).force(true);
            var corner = Particle.DUST.builder().receivers(p).color(cornerColor, cornerSize).count(1).force(true);
            int ei = 0;
            for (double[] q : pts) {
                if (q[3] == 1) corner.location(new Location(w, q[0], q[1], q[2])).spawn();   // corners are never thinned
                else if (ei++ % stride == 0) edge.location(new Location(w, q[0], q[1], q[2])).spawn();
            }
        }
    }

    private static float clampSize(double v) { return (float) Math.max(0.3, Math.min(4.0, v)); }

    private static Color parseColor(String hex) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return Color.fromRGB(Integer.parseInt(h, 16));
        } catch (Exception e) {
            return Color.ORANGE;
        }
    }
}
