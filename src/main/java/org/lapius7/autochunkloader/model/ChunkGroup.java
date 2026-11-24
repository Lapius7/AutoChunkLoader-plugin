package org.lapius7.autochunkloader.model;

import java.time.Instant;
import java.util.UUID;

/** A named rectangle of chunks (chunk coordinates, inclusive) kept loaded together. */
public final class ChunkGroup {
    public static final UUID NO_OWNER = new UUID(0L, 0L);

    private String name;
    private final String world;
    private int minX, minZ, maxX, maxZ;
    private final UUID owner;
    private final Instant created;
    /** true while this group's tickets are held (runtime only). */
    private boolean active;

    public ChunkGroup(String name, String world, int minX, int minZ, int maxX, int maxZ, UUID owner, Instant created) {
        this.name = name;
        this.world = world;
        setBounds(minX, minZ, maxX, maxZ);
        this.owner = owner == null ? NO_OWNER : owner;
        this.created = created == null ? Instant.now() : created;
    }

    public void setBounds(int x1, int z1, int x2, int z2) {
        this.minX = Math.min(x1, x2);
        this.maxX = Math.max(x1, x2);
        this.minZ = Math.min(z1, z2);
        this.maxZ = Math.max(z1, z2);
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

    public int width() { return maxX - minX + 1; }
    public int depth() { return maxZ - minZ + 1; }
    public int chunkCount() { return width() * depth(); }
    public int centerX() { return Math.floorDiv(minX + maxX, 2); }
    public int centerZ() { return Math.floorDiv(minZ + maxZ, 2); }

    public boolean contains(String w, int cx, int cz) {
        return world.equals(w) && cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ;
    }
}
