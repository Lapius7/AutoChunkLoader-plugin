package org.lapius7.autochunkloader.model;

import java.time.Instant;
import java.util.*;

/**
 * A named set of chunks (chunk coordinates) kept loaded together. Usually a rectangle, but single chunks
 * can be added or removed, so it may also be L-shaped or split into separate regions.
 */
public final class ChunkGroup {
    public static final UUID NO_OWNER = new UUID(0L, 0L);

    private String name;
    private final String world;
    private final Set<Long> chunks = new HashSet<>();
    /** Bounding box of {@link #chunks}; recomputed on every change. */
    private int minX, minZ, maxX, maxZ;
    private final UUID owner;
    private final Instant created;
    /** true while group's tickets are held (runtime only). */
    private boolean active;

    public ChunkGroup(String name, String world, int minX, int minZ, int maxX, int maxZ, UUID owner, Instant created) {
        this(name, world, owner, created);
        setBounds(minX, minZ, maxX, maxZ);
    }

    public ChunkGroup(String name, String world, Collection<Long> chunks, UUID owner, Instant created) {
        this(name, world, owner, created);
        setChunks(chunks);
    }

    private ChunkGroup(String name, String world, UUID owner, Instant created) {
        this.name = name;
        this.world = world;
        this.owner = owner == null ? NO_OWNER : owner;
        this.created = created == null ? Instant.now() : created;
    }

    public static long pack(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    public static int unpackX(long k) { return (int) (k >> 32); }
    public static int unpackZ(long k) { return (int) k; }

    /** Replace the chunks with the full rectangle. */
    public void setBounds(int x1, int z1, int x2, int z2) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        chunks.clear();
        for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) chunks.add(pack(x, z));
        recompute();
    }

    public void setChunks(Collection<Long> c) {
        chunks.clear();
        chunks.addAll(c);
        recompute();
    }

    public boolean addChunk(int x, int z) {
        boolean r = chunks.add(pack(x, z));
        if (r) recompute();
        return r;
    }

    public boolean removeChunk(int x, int z) {
        boolean r = chunks.remove(pack(x, z));
        if (r) recompute();
        return r;
    }

    private void recompute() {
        if (chunks.isEmpty()) { minX = minZ = maxX = maxZ = 0; return; }
        minX = minZ = Integer.MAX_VALUE;
        maxX = maxZ = Integer.MIN_VALUE;
        for (long k : chunks) {
            int x = unpackX(k), z = unpackZ(k);
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
    }

    public String name() { return name; }
    public void rename(String n) { this.name = n; }
    public String world() { return world; }
    public int minX() { return minX; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxZ() { return maxZ; }
    public UUID owner() { return owner; }
    public Instant created() { return created; }
    public boolean active() { return active; }
    public void active(boolean a) { this.active = a; }

    /** Read-only view of the member chunks (packed). */
    public Set<Long> chunks() { return Collections.unmodifiableSet(chunks); }
    public boolean isEmpty() { return chunks.isEmpty(); }

    /** Bounding-box width/depth. */
    public int width() { return maxX - minX + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public int chunkCount() { return chunks.size(); }
    public int centerX() { return Math.floorDiv(minX + maxX, 2); }
    public int centerZ() { return Math.floorDiv(minZ + maxZ, 2); }

    /** True when the chunks fill their bounding box. */
    public boolean isRectangle() { return (long) width() * depth() == chunks.size(); }

    public boolean contains(String w, int cx, int cz) {
        return world.equals(w) && chunks.contains(pack(cx, cz));
    }

    /** Number of 4-connected separate regions. */
    public int regions() {
        Set<Long> left = new HashSet<>(chunks);
        int n = 0;
        ArrayDeque<Long> q = new ArrayDeque<>();
        while (!left.isEmpty()) {
            long s = left.iterator().next();
            left.remove(s);
            q.add(s);
            n++;
            while (!q.isEmpty()) {
                long k = q.poll();
                int x = unpackX(k), z = unpackZ(k);
                for (long nb : new long[]{pack(x + 1, z), pack(x - 1, z), pack(x, z + 1), pack(x, z - 1)})
                    if (left.remove(nb)) q.add(nb);
            }
        }
        return n;
    }

    /**
     * Chunks after growing ({@code d > 0}) or shrinking ({@code d < 0}) by |d| on every side.
     * Grow adds all chunks within the square distance; shrink keeps only chunks whose whole square neighbourhood is members.
     */
    public Set<Long> resized(int d) {
        Set<Long> out = new HashSet<>();
        if (isRectangle()) {
            int x1 = minX - d, z1 = minZ - d, x2 = maxX + d, z2 = maxZ + d;
            if (x1 > x2 || z1 > z2) return out;
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) out.add(pack(x, z));
            return out;
        }
        int r = Math.abs(d);
        if (d > 0) {
            for (long k : chunks) {
                int x = unpackX(k), z = unpackZ(k);
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) out.add(pack(x + dx, z + dz));
            }
        } else {
            outer:
            for (long k : chunks) {
                int x = unpackX(k), z = unpackZ(k);
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
                    if (!chunks.contains(pack(x + dx, z + dz))) continue outer;
                out.add(k);
            }
        }
        return out;
    }
}
