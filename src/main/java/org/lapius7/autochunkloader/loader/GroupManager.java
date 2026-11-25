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

    public static long pack(int x, int z) { return ChunkGroup.pack(x, z); }
    public static int unpackX(long k) { return ChunkGroup.unpackX(k); }
    public static int unpackZ(long k) { return ChunkGroup.unpackZ(k); }

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

    /** Replace the whole chunk set (resize / expand). */
    public void replaceChunks(ChunkGroup g, Collection<Long> chunks) {
        release(g);
        g.setChunks(chunks);
        apply(g);
        changed();
    }

    /** Add one chunk to the group, taking its ticket if the group is active. */
    public boolean addChunk(ChunkGroup g, int x, int z) {
        if (!g.addChunk(x, z)) return false;
        if (g.active()) ref(g.world(), pack(x, z));
        changed();
        return true;
    }

    /** Remove one chunk from the group, dropping its ticket when no other group holds it. Caller handles the empty case. */
    public boolean removeChunk(ChunkGroup g, int x, int z) {
        if (!g.removeChunk(x, z)) return false;
        if (g.active()) unref(g.world(), pack(x, z));
        changed();
        return true;
    }

    public void rename(ChunkGroup g, String newName) {
        groups.remove(g.name().toLowerCase(Locale.ROOT));
        g.rename(newName);
        groups.put(newName.toLowerCase(Locale.ROOT), g);
        changed();
    }

    /** Persist a flag change (spawn / randomtick on/off). */
    public void touch() { changed(); }

    private void changed() {
        version++;
        save();
    }

    // ---- tickets ----
    private void ref(String world, long k) {
        World w = Bukkit.getWorld(world);
        if (w == null) return;
        Map<Long, Integer> m = refs.computeIfAbsent(world, x -> new HashMap<>());
        if (m.merge(k, 1, Integer::sum) == 1) w.addPluginChunkTicket(unpackX(k), unpackZ(k), plugin);
    }

    private void unref(String world, long k) {
        Map<Long, Integer> m = refs.get(world);
        if (m == null) return;
        Integer n = m.get(k);
        if (n == null) return;
        if (n <= 1) {
            m.remove(k);
            World w = Bukkit.getWorld(world);
            if (w != null) w.removePluginChunkTicket(unpackX(k), unpackZ(k), plugin);
        } else m.put(k, n - 1);
    }

    public void apply(ChunkGroup g) {
        if (g.active()) return;
        if (Bukkit.getWorld(g.world()) == null) return;
        for (long k : g.chunks()) ref(g.world(), k);
        g.active(true);
    }

    public void release(ChunkGroup g) {
        if (!g.active()) return;
        for (long k : g.chunks()) unref(g.world(), k);
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
                List<Integer> cl = s.getIntegerList("chunks"); // x,z,x,z,... (non-rectangular groups)
                if (cl.isEmpty() && b.size() != 4) throw new IllegalArgumentException("needs bounds (4 numbers) or chunks");
                if (!cl.isEmpty() && cl.size() % 2 != 0) throw new IllegalArgumentException("chunks must be x,z pairs");
                UUID owner = s.contains("owner") ? UUID.fromString(Objects.requireNonNull(s.getString("owner"))) : null;
                Instant created = s.contains("created") ? Instant.parse(Objects.requireNonNull(s.getString("created"))) : null;
                String world = Objects.requireNonNull(s.getString("world"));
                ChunkGroup g;
                if (!cl.isEmpty()) {
                    List<Long> set = new ArrayList<>();
                    for (int i = 0; i < cl.size(); i += 2) set.add(pack(cl.get(i), cl.get(i + 1)));
                    g = new ChunkGroup(name, world, set, owner, created);
                } else {
                    g = new ChunkGroup(name, world, b.get(0), b.get(1), b.get(2), b.get(3), owner, created);
                }
                g.spawn(s.getBoolean("spawn", false));
                g.randomTick(s.getBoolean("randomtick", true));
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
            if (g.isRectangle()) {
                y.set(p + ".bounds", List.of(g.minX(), g.minZ(), g.maxX(), g.maxZ()));
            } else {
                List<Integer> flat = new ArrayList<>();
                g.chunks().stream().sorted().forEach(k -> { flat.add(unpackX(k)); flat.add(unpackZ(k)); });
                y.set(p + ".chunks", flat);
            }
            y.set(p + ".owner", g.owner().toString());
            y.set(p + ".created", g.created().toString());
            if (g.spawn()) y.set(p + ".spawn", true);
            if (!g.randomTick()) y.set(p + ".randomtick", false);
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
