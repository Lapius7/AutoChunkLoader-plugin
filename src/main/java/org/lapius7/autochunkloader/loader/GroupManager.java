package org.lapius7.autochunkloader.loader;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.lapius7.autochunkloader.model.ChunkGroup;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;

/** Owns all groups, the persisted data file and the (ref-counted) plugin chunk tickets. */
public final class GroupManager {
    private final Plugin plugin;
    private final Path dataFile;
    private final Map<String, ChunkGroup> groups = new LinkedHashMap<>(); // key: lower-case name
    /** world name -> packed chunk -> number of active groups holding it */
    private final Map<String, Map<Long, Integer>> refs = new HashMap<>();
    private int version;

    public GroupManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = plugin.getDataFolder().toPath().resolve("data.yml");
    }

    public static long pack(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    public static int unpackX(long k) { return (int) (k >> 32); }
    public static int unpackZ(long k) { return (int) k; }

    /** Bumped on any change; the inspector uses it to invalidate its outline cache. */
    public int version() { return version; }

    // ---- queries ----
    public Collection<ChunkGroup> all() { return Collections.unmodifiableCollection(groups.values()); }
    public ChunkGroup get(String name) { return name == null ? null : groups.get(name.toLowerCase(Locale.ROOT)); }
    public int totalChunks() { return groups.values().stream().mapToInt(ChunkGroup::chunkCount).sum(); }
    public long countOwnedBy(UUID u) { return groups.values().stream().filter(g -> g.owner().equals(u)).count(); }

    public List<ChunkGroup> groupsAt(String world, int cx, int cz) {
        List<ChunkGroup> out = new ArrayList<>();
        for (ChunkGroup g : groups.values()) if (g.contains(world, cx, cz)) out.add(g);
        return out;
    }

    /** Distinct chunks currently ticketed by this plugin in the world. */
    public Set<Long> ticketedChunks(String world) {
        Map<Long, Integer> m = refs.get(world);
        return m == null ? Set.of() : Collections.unmodifiableSet(m.keySet());
    }

    // ---- mutations ----
    public void add(ChunkGroup g) {
        groups.put(g.name().toLowerCase(Locale.ROOT), g);
        apply(g);
        changed();
    }

    public void remove(ChunkGroup g) {
        release(g);
        groups.remove(g.name().toLowerCase(Locale.ROOT));
        changed();
    }

    public void resize(ChunkGroup g, int minX, int minZ, int maxX, int maxZ) {
        release(g);
        g.setBounds(minX, minZ, maxX, maxZ);
        apply(g);
        changed();
    }

    public void rename(ChunkGroup g, String newName) {
        groups.remove(g.name().toLowerCase(Locale.ROOT));
        g.rename(newName);
        groups.put(newName.toLowerCase(Locale.ROOT), g);
        changed();
    }

    private void changed() {
        version++;
        save();
    }

    // ---- tickets ----
    public void apply(ChunkGroup g) {
        if (g.active()) return;
        World w = Bukkit.getWorld(g.world());
        if (w == null) return;
        Map<Long, Integer> m = refs.computeIfAbsent(g.world(), k -> new HashMap<>());
        for (int x = g.minX(); x <= g.maxX(); x++) {
            for (int z = g.minZ(); z <= g.maxZ(); z++) {
                if (m.merge(pack(x, z), 1, Integer::sum) == 1) w.addPluginChunkTicket(x, z, plugin);
            }
        }
        g.active(true);
    }

    public void release(ChunkGroup g) {
        if (!g.active()) return;
        World w = Bukkit.getWorld(g.world());
        Map<Long, Integer> m = refs.get(g.world());
        if (m != null) {
            for (int x = g.minX(); x <= g.maxX(); x++) {
                for (int z = g.minZ(); z <= g.maxZ(); z++) {
                    long k = pack(x, z);
                    Integer n = m.get(k);
                    if (n == null) continue;
                    if (n <= 1) {
                        m.remove(k);
                        if (w != null) w.removePluginChunkTicket(x, z, plugin);
                    } else m.put(k, n - 1);
                }
            }
        }
        g.active(false);
    }

    public void applyAll() {
        for (ChunkGroup g : groups.values()) {
            if (g.active()) continue;
            if (Bukkit.getWorld(g.world()) == null) {
                plugin.getLogger().warning("Group '" + g.name() + "': world '" + g.world() + "' is not loaded; skipped (data kept).");
                continue;
            }
            apply(g);
        }
    }

    public void applyWorld(World w) {
        for (ChunkGroup g : groups.values()) if (g.world().equals(w.getName())) apply(g);
        version++;
    }

    /** World is unloading: forget its tickets (the server drops them with the world). */
    public void releaseWorld(String world) {
        for (ChunkGroup g : groups.values()) if (g.world().equals(world)) g.active(false);
        refs.remove(world);
        version++;
    }

    public void releaseAll() {
        for (World w : Bukkit.getWorlds()) w.removePluginChunkTickets(plugin);
        for (ChunkGroup g : groups.values()) g.active(false);
        refs.clear();
    }

    // ---- persistence ----
    public void load() {
        groups.clear();
        if (!Files.exists(dataFile)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile.toFile());
        ConfigurationSection sec = y.getConfigurationSection("groups");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(name);
            if (s == null) continue;
            try {
                List<Integer> b = s.getIntegerList("bounds"); // [minX, minZ, maxX, maxZ]
                if (b.size() != 4) throw new IllegalArgumentException("bounds must have 4 numbers");
                UUID owner = s.contains("owner") ? UUID.fromString(Objects.requireNonNull(s.getString("owner"))) : null;
                Instant created = s.contains("created") ? Instant.parse(Objects.requireNonNull(s.getString("created"))) : null;
                ChunkGroup g = new ChunkGroup(name, Objects.requireNonNull(s.getString("world")),
                        b.get(0), b.get(1), b.get(2), b.get(3), owner, created);
                groups.put(name.toLowerCase(Locale.ROOT), g);
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping invalid group '" + name + "' in data.yml: " + e.getMessage());
            }
        }
        version++;
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (ChunkGroup g : groups.values()) {
            String p = "groups." + g.name();
            y.set(p + ".world", g.world());
            y.set(p + ".bounds", List.of(g.minX(), g.minZ(), g.maxX(), g.maxZ()));
            y.set(p + ".owner", g.owner().toString());
            y.set(p + ".created", g.created().toString());
        }
        try {
            Files.createDirectories(dataFile.getParent());
            Path tmp = dataFile.resolveSibling("data.yml.tmp");
            Files.writeString(tmp, y.saveToString());
            try {
                Files.move(tmp, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save data.yml", e);
        }
    }
}
