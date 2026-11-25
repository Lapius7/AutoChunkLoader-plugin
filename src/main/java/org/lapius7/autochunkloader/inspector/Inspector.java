package org.lapius7.autochunkloader.inspector;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.lapius7.autochunkloader.AutoChunkLoaderPlugin;
import org.lapius7.autochunkloader.loader.GroupManager;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

/** Draws the outer outline of all kept chunks with particles, sent only to players who toggled it on. */
public final class Inspector {
    /** A horizontal outline segment in block coordinates (axis-aligned). */
    private record Seg(double x1, double z1, double x2, double z2) { }

    private final AutoChunkLoaderPlugin plugin;
    private final GroupManager mgr;
    /** Players who toggled their own outline. Kept while offline and saved to inspector.yml. */
    private final Map<UUID, Boolean> own = new HashMap<>();
    /** Admin switch (/acl inspectorall): draws for every online player regardless of own. */
    private boolean forceAll;
    private final Path file;
    /** Outline of one world: boundary segments, the true corner vertices and the bounding box {minX, minZ, maxX, maxZ} (block coords). */
    private record Outline(List<Seg> segs, List<double[]> corners, double[] bounds) { }

    private final Map<String, Outline> cache = new HashMap<>();
    private int cacheVersion = -1;
    private BukkitTask task;

    public Inspector(AutoChunkLoaderPlugin plugin, GroupManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.file = plugin.getDataFolder().toPath().resolve("inspector.yml");
    }

    /** The player's own toggle (what /acl i shows), ignoring the forced-all switch. */
    public boolean enabled(Player p) { return own.getOrDefault(p.getUniqueId(), false); }

    public void set(Player p, boolean on) {
        own.put(p.getUniqueId(), on);
        save();
    }

    public boolean forceAll() { return forceAll; }

    public void setForceAll(boolean on) {
        forceAll = on;
        save();
    }

    // ---- persistence (inspector.yml) ----
    public void load() {
        own.clear();
        forceAll = false;
        if (!Files.exists(file)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
        forceAll = y.getBoolean("all", false);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec != null) for (String k : sec.getKeys(false)) {
            try { own.put(UUID.fromString(k), sec.getBoolean(k)); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("Skipping invalid UUID '" + k + "' in inspector.yml"); }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("all", forceAll);
        for (Map.Entry<UUID, Boolean> e : own.entrySet()) y.set("players." + e.getKey(), e.getValue());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("inspector.yml.tmp");
            Files.writeString(tmp, y.saveToString());
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save inspector.yml", e);
        }
    }

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
        cache.clear();
    }

    // ------------------------------------------------------------------
    private Outline outline(String world) {
        if (cacheVersion != mgr.version()) { cache.clear(); cacheVersion = mgr.version(); }
        return cache.computeIfAbsent(world, w -> build(mgr.ticketedChunks(w)));
    }

    /** Boundary segments and true corners of a set of packed chunk keys. Pure geometry (no Bukkit). */
    static Outline build(Set<Long> set) {
        List<Seg> out = new ArrayList<>();
        Set<Long> verts = new HashSet<>();
        for (long k : set) {
            int x = GroupManager.unpackX(k), z = GroupManager.unpackZ(k);
            double x0 = x * 16.0, z0 = z * 16.0, x1 = x0 + 16, z1 = z0 + 16;
            if (!set.contains(GroupManager.pack(x - 1, z))) out.add(new Seg(x0, z0, x0, z1));
            if (!set.contains(GroupManager.pack(x + 1, z))) out.add(new Seg(x1, z0, x1, z1));
            if (!set.contains(GroupManager.pack(x, z - 1))) out.add(new Seg(x0, z0, x1, z0));
            if (!set.contains(GroupManager.pack(x, z + 1))) out.add(new Seg(x0, z1, x1, z1));
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) verts.add(GroupManager.pack(x + dx, z + dz));
        }
        // A vertex is a true corner unless it lies on a straight edge (exactly two chunks around it, side by side).
        List<double[]> corners = new ArrayList<>();
        for (long v : verts) {
            int vx = GroupManager.unpackX(v), vz = GroupManager.unpackZ(v);
            boolean a = set.contains(GroupManager.pack(vx - 1, vz - 1)), b = set.contains(GroupManager.pack(vx, vz - 1));
            boolean c = set.contains(GroupManager.pack(vx - 1, vz)), d = set.contains(GroupManager.pack(vx, vz));
            int n = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0);
            boolean straight = n == 2 && ((a == b) || (a == c)) ;
            if (n == 0 || n == 4 || straight) continue;
            corners.add(new double[]{vx * 16.0, vz * 16.0});
        }
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Seg g : out) {
            b[0] = Math.min(b[0], Math.min(g.x1, g.x2)); b[1] = Math.min(b[1], Math.min(g.z1, g.z2));
            b[2] = Math.max(b[2], Math.max(g.x1, g.x2)); b[3] = Math.max(b[3], Math.max(g.z1, g.z2));
        }
        return new Outline(out, corners, b);
    }

    /** Distance-based spacing multiplier: 1 near, up to 4 far. */
    private static double spread(double dist, double falloff) {
        return Math.min(4.0, 1.0 + Math.max(0, dist - 16) / falloff);
    }

    /**
     * edges mode: only the outline edges. Horizontal lines at the bottom and top of the band
     * and a vertical pillar at each true corner. Nothing on the walls or in the hollow.
     * Points are {x, y, z, corner(1)/edge(0)}.
     */
    static List<double[]> edgesPoints(Outline o, double ex, double ez, double radius,
                                      double yLo, double yHi, double step, double falloff) {
        List<double[]> pts = new ArrayList<>();
        for (Seg s : o.segs) {
            double mx = (s.x1 + s.x2) / 2, mz = (s.z1 + s.z2) / 2;
            if (Math.abs(mx - ex) > radius || Math.abs(mz - ez) > radius) continue;
            double k = spread(Math.hypot(mx - ex, mz - ez), falloff);
            double len = Math.abs(s.x2 - s.x1) + Math.abs(s.z2 - s.z1);
            int n = Math.max(1, (int) Math.round(len / (step * k)));
            for (double y : new double[]{yLo, yHi})
                for (int i = 0; i <= n; i++) {
                    double t = (double) i / n;
                    pts.add(new double[]{s.x1 + (s.x2 - s.x1) * t, y, s.z1 + (s.z2 - s.z1) * t, 0});
                }
        }
        for (double[] q : o.corners) {
            if (Math.abs(q[0] - ex) > radius || Math.abs(q[1] - ez) > radius) continue;
            double k = spread(Math.hypot(q[0] - ex, q[1] - ez), falloff);
            double st = step * k;
            int n = Math.max(1, (int) Math.ceil((yHi - yLo) / st));
            for (int i = 0; i <= n; i++) pts.add(new double[]{q[0], Math.min(yHi, yLo + i * st), q[1], 1});
        }
        return pts;
    }

    /** full mode (legacy): horizontal rings and vertical posts over the whole wall. */
    private static List<double[]> fullPoints(Outline o, double ex, double ez, double radius, double yLo, double yHi,
                                             double step, double ring, double post, double falloff) {
        List<double[]> pts = new ArrayList<>();
        Set<Long> corners = new HashSet<>();
        for (Seg s : o.segs) {
            double mx = (s.x1 + s.x2) / 2, mz = (s.z1 + s.z2) / 2;
            if (Math.abs(mx - ex) > radius || Math.abs(mz - ez) > radius) continue;
            double k = spread(Math.hypot(mx - ex, mz - ez), falloff);
            double len = Math.abs(s.x2 - s.x1) + Math.abs(s.z2 - s.z1);
            int n = Math.max(1, (int) Math.round(len / (step * k)));
            for (double y = Math.ceil(yLo / ring) * ring; y <= yHi; y += ring * k)
                for (int i = 0; i <= n; i++) {
                    double t = (double) i / n;
                    pts.add(new double[]{s.x1 + (s.x2 - s.x1) * t, y, s.z1 + (s.z2 - s.z1) * t, 0});
                }
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
            double k = spread(Math.hypot(cx - ex, cz - ez), falloff);
            for (double y = yLo; y <= yHi; y += step * k) pts.add(new double[]{cx, y, cz, 1});
        }
        return pts;
    }

    private void tick() {
        if (!forceAll && !own.containsValue(true)) return;
        FileConfiguration c = plugin.getConfig();
        double radiusBlocks = Math.max(1, c.getInt("inspector.radius", 6)) * 16.0;
        double height = Math.max(1, c.getDouble("inspector.height", 16));
        double step = Math.max(0.25, c.getDouble("inspector.step", 0.5));
        double ring = Math.max(1, c.getDouble("inspector.ring-spacing", 2));
        double post = Math.max(2, c.getDouble("inspector.post-spacing", 8));
        double falloff = Math.max(8, c.getDouble("inspector.falloff", 24));
        boolean edges = !"full".equalsIgnoreCase(c.getString("inspector.mode", "edges"));
        int max = Math.max(50, c.getInt("inspector.max-particles", 1500));
        int maxForced = Math.max(50, c.getInt("inspector.all-max-particles", 600));
        Color edgeColor = parseColor(c.getString("inspector.color", "#ffa200"));
        Color cornerColor = parseColor(c.getString("inspector.corner-color", "#ff1f6d"));
        float size = clampSize(c.getDouble("inspector.size", 1.6));
        float cornerSize = clampSize(c.getDouble("inspector.corner-size", 2.4));

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean mine = own.getOrDefault(p.getUniqueId(), false);
            if (!mine && !forceAll) continue;
            World w = p.getWorld();
            Outline o = outline(w.getName());
            if (o.segs.isEmpty()) continue;
            Location eye = p.getEyeLocation();
            double ex = eye.getX(), ez = eye.getZ(), ey = eye.getY();
            // too far from every registered chunk: draw nothing, skip the per-segment work
            if (ex < o.bounds[0] - radiusBlocks || ex > o.bounds[2] + radiusBlocks
                    || ez < o.bounds[1] - radiusBlocks || ez > o.bounds[3] + radiusBlocks) continue;
            int limit = mine ? max : maxForced;
            double yLo = Math.max(w.getMinHeight(), ey - height), yHi = Math.min(w.getMaxHeight(), ey + height);

            List<double[]> pts;
            if (edges) {
                // snap the band to a 4-block grid so the lines do not slide while the player moves
                double lo = Math.max(w.getMinHeight(), Math.floor(yLo / 4) * 4), hi = Math.min(w.getMaxHeight(), Math.ceil(yHi / 4) * 4);
                pts = edgesPoints(o, ex, ez, radiusBlocks, lo, hi, step, falloff);
            } else {
                pts = fullPoints(o, ex, ez, radiusBlocks, yLo, yHi, step, ring, post, falloff);
            }
            if (pts.isEmpty()) continue;

            long nCorner = pts.stream().filter(q -> q[3] == 1).count();
            int stride = Math.max(1, (int) Math.ceil((pts.size() - nCorner) / (double) Math.max(50, limit - nCorner)));
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
