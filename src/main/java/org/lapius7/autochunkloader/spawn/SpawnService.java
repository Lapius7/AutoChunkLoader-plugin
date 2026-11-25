package org.lapius7.autochunkloader.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntityType;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;
import org.lapius7.autochunkloader.AutoChunkLoaderPlugin;
import org.lapius7.autochunkloader.loader.GroupManager;
import org.lapius7.autochunkloader.model.ChunkGroup;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Vanilla natural mob spawning for the chunks of groups that have {@code spawn} on, with the one rule
 * "a player must be within 128 blocks" removed.
 *
 * <p>Chunks that a player is close enough to are left to vanilla. For every other registered chunk this runs
 * what {@code ServerChunkCache.tickChunks} gives a spawning chunk each tick: inhabited time, thunder and one
 * spawn attempt per category. The attempt is a copy of {@code NaturalSpawner.spawnCategoryForPosition}
 * (Paper 26.3) in which the nearest-player distance is replaced by "in range"; biome mob lists, structure
 * spawns, light and floor rules, spawn cost, group sizes, spawn events and the spawn interval are vanilla's.
 * The mob cap is the one a player gets, shared with all mobs in the world.
 */
public final class SpawnService implements Listener {
    private static final MobCategory[] CATEGORIES = NaturalSpawner.SPAWNING_CATEGORIES;
    /** Chunks covered by one player's spawn area (17 x 17); vanilla's MAGIC_NUMBER. */
    private static final int AREA_CHUNKS = 17 * 17;

    private final AutoChunkLoaderPlugin plugin;
    private final GroupManager mgr;
    private BukkitTask task;
    /** Mobs we set persistenceRequired on; they are released as soon as they leave a chunk we serve. */
    private final Set<Mob> tagged = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final NamespacedKey markKey;
    /** World name to packed chunk keys served (no player in range) this tick. */
    private final Map<String, Set<Long>> served = new HashMap<>();
    private boolean enabled;

    private MethodHandle getRandomPosWithin, getRandomSpawnMobAt, canSpawnMobAt, getMobForSpawn;
    private MethodHandle stateCanSpawn, stateAfterSpawn, stateSpawnableChunks;
    private boolean handlesOk;

    public SpawnService(AutoChunkLoaderPlugin plugin, GroupManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.markKey = new NamespacedKey(plugin, "far");
    }

    public void start() {
        stop();
        enabled = plugin.getConfig().getBoolean("spawn.enabled", true);
        if (!enabled) return;
        initHandles();
        if (!handlesOk) { enabled = false; return; }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        HandlerList.unregisterAll(this);
        for (Mob m : new ArrayList<>(tagged)) release(m);
        tagged.clear();
    }

    private void release(Mob m) {
        m.persistenceRequired = false;
        m.getBukkitEntity().getPersistentDataContainer().remove(markKey);
    }

    private void protect(Mob m) {
        if (tagged.contains(m) || m.isPersistenceRequired() || m.requiresCustomPersistence()) return;
        m.persistenceRequired = true;
        m.getBukkitEntity().getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
        tagged.add(m);
    }

    /** A mob saved with our marker (shutdown, crash or unload while tagged) goes back to vanilla persistence. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (org.bukkit.entity.Entity en : e.getEntities()) {
            if (en.getPersistentDataContainer().has(markKey, PersistentDataType.BYTE)) {
                en.getPersistentDataContainer().remove(markKey);
                if (((org.bukkit.craftbukkit.entity.CraftEntity) en).getHandle() instanceof Mob m) m.persistenceRequired = false;
            }
        }
    }

    /** false when disabled in config (or this server's NMS does not match). */
    public boolean enabled() { return enabled; }

    private void initHandles() {
        if (handlesOk) return;
        try {
            MethodHandles.Lookup ns = MethodHandles.privateLookupIn(NaturalSpawner.class, MethodHandles.lookup());
            getRandomPosWithin = ns.findStatic(NaturalSpawner.class, "getRandomPosWithin",
                    MethodType.methodType(BlockPos.class, Level.class, LevelChunk.class));
            getRandomSpawnMobAt = ns.findStatic(NaturalSpawner.class, "getRandomSpawnMobAt",
                    MethodType.methodType(Optional.class, ServerLevel.class, StructureManager.class, ChunkGenerator.class,
                            MobCategory.class, RandomSource.class, BlockPos.class));
            canSpawnMobAt = ns.findStatic(NaturalSpawner.class, "canSpawnMobAt",
                    MethodType.methodType(boolean.class, ServerLevel.class, StructureManager.class, ChunkGenerator.class,
                            MobCategory.class, MobSpawnSettings.SpawnerData.class, BlockPos.class));
            getMobForSpawn = ns.findStatic(NaturalSpawner.class, "getMobForSpawn",
                    MethodType.methodType(Mob.class, ServerLevel.class, EntityType.class));
            MethodHandles.Lookup st = MethodHandles.privateLookupIn(NaturalSpawner.SpawnState.class, MethodHandles.lookup());
            stateCanSpawn = st.findVirtual(NaturalSpawner.SpawnState.class, "canSpawn",
                    MethodType.methodType(boolean.class, EntityType.class, Level.class, BlockPos.class, ChunkAccess.class));
            stateAfterSpawn = st.findVirtual(NaturalSpawner.SpawnState.class, "afterSpawn",
                    MethodType.methodType(void.class, Mob.class, ChunkAccess.class));
            stateSpawnableChunks = st.findGetter(NaturalSpawner.SpawnState.class, "spawnableChunkCount", int.class);
            handlesOk = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            handlesOk = false;
            plugin.getLogger().severe("Natural spawning disabled: this server's NaturalSpawner does not match Paper 26.3 (" + e + ")");
        }
    }

    /** Per-world data for one tick. */
    private static final class Ctx {
        ServerLevel level;
        ServerChunkCache cache;
        NaturalSpawner.SpawnState state;
        final List<MobCategory> categories = new ArrayList<>();
        final int[] limits = new int[MobCategory.values().length];
        boolean ok;
    }

    private void tick() {
        Map<String, Ctx> ctxs = new HashMap<>();
        Map<String, List<LevelChunk>> chunksByWorld = new HashMap<>();
        served.clear();
        for (ChunkGroup g : mgr.all()) {
            if (!g.spawn() || !g.active()) continue;
            Ctx cx = ctxs.computeIfAbsent(g.world(), this::context);
            if (!cx.ok) continue;
            collect(cx, g, chunksByWorld.computeIfAbsent(g.world(), w -> new ArrayList<>()));
        }
        for (Map.Entry<String, List<LevelChunk>> e : chunksByWorld.entrySet()) {
            try {
                processWorld(ctxs.get(e.getKey()), e.getKey(), e.getValue());
            } catch (Throwable ex) {
                plugin.getLogger().warning("Natural spawning failed in world '" + e.getKey() + "': " + ex);
            }
        }
        tagged.removeIf(m -> {
            Set<Long> ok = served.get(m.level().getWorld().getName());
            boolean keep = !m.isRemoved() && ok != null && ok.contains(ChunkGroup.pack(m.blockPosition().getX() >> 4, m.blockPosition().getZ() >> 4));
            if (!keep) release(m);
            return !keep;
        });
    }

    /** Vanilla's per-level gate and per-category schedule (ServerChunkCache.tickChunks + getFilteredSpawningCategories). */
    private Ctx context(String world) {
        Ctx c = new Ctx();
        World w = Bukkit.getWorld(world);
        if (w == null) return c;
        c.level = ((CraftWorld) w).getHandle();
        c.cache = c.level.getChunkSource();
        if (!c.level.getGameRules().get(GameRules.SPAWN_MOBS)) return c;
        c.state = c.cache.getLastSpawnState();
        if (c.state == null) return c;
        boolean friendlies = c.cache.spawnFriendlies, enemies = c.cache.spawnEnemies;
        if (!friendlies && !enemies) return c;
        long time = c.level.getGameTime();
        long animalTicks = w.getTicksPerSpawns(SpawnCategory.ANIMAL);
        boolean persistent = animalTicks != 0 && time % animalTicks == 0;
        for (MobCategory cat : CATEGORIES) {
            SpawnCategory sc = CraftSpawnCategory.toBukkit(cat);
            int limit = cat.getMaxInstancesPerChunk();
            if (CraftSpawnCategory.isValidForLimits(sc)) {
                long ticks = w.getTicksPerSpawns(sc);
                if (ticks == 0 || time % ticks != 0) continue;
                limit = w.getSpawnLimit(sc);
            }
            if (limit == 0) continue;
            if ((friendlies || !cat.isFriendly()) && (enemies || cat.isFriendly()) && (persistent || !cat.isPersistent())) {
                c.categories.add(cat);
                c.limits[cat.ordinal()] = limit;
            }
        }
        c.ok = true;
        return c;
    }

    /** Chunks vanilla does not handle: loaded and entity-ticking, no player in spawn range. */
    private void collect(Ctx cx, ChunkGroup g, List<LevelChunk> out) {
        Set<Long> ok = served.computeIfAbsent(g.world(), w -> new HashSet<>());
        for (long k : g.chunks()) {
            LevelChunk chunk = cx.cache.getChunkNow(ChunkGroup.unpackX(k), ChunkGroup.unpackZ(k));
            if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) continue;
            if (!cx.cache.chunkMap.getPlayersCloseForSpawning(chunk.getPos()).isEmpty()) continue;
            if (!cx.level.getWorldBorder().isWithinBounds(chunk.getPos())) continue;
            if (ok.add(k)) out.add(chunk);
        }
    }

    /**
     * One mob cap for the whole world, shared with everything else in it. Every mob of the category in the world
     * counts (as in NaturalSpawner.createState) against the cap a player gets: the spawn limit itself with Paper's
     * per-player-mob-spawns (the default), otherwise vanilla's limit * chunks / 289 with the chunks served here
     * added to the spawnable chunk count.
     */
    private void processWorld(Ctx cx, String world, List<LevelChunk> chunks) throws Throwable {
        if (chunks.isEmpty()) return;
        Collections.shuffle(chunks, ThreadLocalRandom.current());
        int[] cnt = countMobs(cx, world);
        int spawnable = (int) stateSpawnableChunks.invoke(cx.state);
        int[] cap = new int[MobCategory.values().length];
        boolean perPlayer = cx.level.paperConfig().entities.spawning.perPlayerMobSpawns;
        for (MobCategory cat : cx.categories) {
            int limit = cx.limits[cat.ordinal()];
            // Paper's default (per-player-mob-spawns) gives every player a flat limit; vanilla scales it by chunk count.
            cap[cat.ordinal()] = perPlayer ? limit : (int) ((long) limit * (spawnable + chunks.size()) / AREA_CHUNKS);
        }
        for (LevelChunk chunk : chunks) {
            chunk.incrementInhabitedTime();
            cx.level.tickThunder(chunk);
            for (MobCategory cat : cx.categories) {
                int left = cap[cat.ordinal()] - cnt[cat.ordinal()];
                if (left <= 0) continue;
                spawnCategoryForChunk(cx, cat, chunk, left, cnt);
            }
        }
    }

    /**
     * Mob counts per category in the whole world, counted like NaturalSpawner.createState (persistent mobs
     * are skipped; Paper's count-all-mobs-for-spawning is honoured). Also protects mobs in served chunks.
     */
    private int[] countMobs(Ctx cx, String world) {
        int[] cnt = new int[MobCategory.values().length];
        boolean countAll = cx.level.paperConfig().entities.spawning.countAllMobsForSpawning;
        Set<Long> ok = served.getOrDefault(world, Set.of());
        for (net.minecraft.world.entity.Entity en : cx.level.getAllEntities()) {
            if (!(en instanceof Mob mob)) continue;
            long key = ChunkGroup.pack(mob.blockPosition().getX() >> 4, mob.blockPosition().getZ() >> 4);
            if (counts(mob, countAll, tagged.contains(mob))) cnt[mob.getType().getCategory().ordinal()]++;
            if (ok.contains(key) && mob.getType().getCategory() != MobCategory.MISC && isNaturalOrChunkGen(mob)) protect(mob);
        }
        return cnt;
    }

    private boolean isNaturalOrChunkGen(Mob mob) {
        return mob.spawnReason == CreatureSpawnEvent.SpawnReason.NATURAL || mob.spawnReason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN;
    }

    private boolean counts(Mob mob, boolean countAll, boolean wasTagged) {
        if (!wasTagged && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) return false;
        if (mob.getType().getCategory() == MobCategory.MISC) return false;
        return countAll || mob.spawnReason == CreatureSpawnEvent.SpawnReason.NATURAL
                || mob.spawnReason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN;
    }

    // ------------------------------------------------------------------ vanilla spawn attempt

    private void spawnCategoryForChunk(Ctx cx, MobCategory cat, LevelChunk chunk, int maxSpawns, int[] cnt) {
        try {
            BlockPos start = (BlockPos) getRandomPosWithin.invoke((Level) cx.level, chunk);
            if (start.getY() >= cx.level.getMinY() + 1) spawnCategoryForPosition(cx, cat, chunk, start, maxSpawns, cnt);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /**
     * Copy of {@code NaturalSpawner.spawnCategoryForPosition} (Paper 26.3). Differences, all about the nearest
     * player: no player at all is not a reason to skip the position, and the 128-block checks see "in range".
     * A real player closer than 24 blocks still blocks the position, as in vanilla.
     */
    private void spawnCategoryForPosition(Ctx cx, MobCategory cat, LevelChunk chunk, BlockPos start, int maxSpawns, int[] cnt)
            throws Throwable {
        ServerLevel level = cx.level;
        StructureManager structureManager = level.structureManager();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int yStart = start.getY();
        BlockState state = level.getBlockStateIfLoadedAndInBounds(start);
        if (state == null || state.isRedstoneConductor(chunk, start)) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int clusterSize = 0;
        for (int groupCount = 0; groupCount < 3; groupCount++) {
            int x = start.getX();
            int z = start.getZ();
            MobSpawnSettings.SpawnerData currentSpawnData = null;
            SpawnGroupData groupData = null;
            int max = Mth.ceil(level.getRandom().nextFloat() * 4.0F);
            int groupSize = 0;

            for (int ll = 0; ll < max; ll++) {
                x += level.getRandom().nextInt(6) - level.getRandom().nextInt(6);
                z += level.getRandom().nextInt(6) - level.getRandom().nextInt(6);
                pos.set(x, yStart, z);
                double xx = x + 0.5;
                double zz = z + 0.5;
                Player nearest = level.getNearestPlayer(xx, yStart, zz, -1.0, false);
                double playerDistSqr = nearest == null ? Double.POSITIVE_INFINITY : nearest.distanceToSqr(xx, yStart, zz);
                double despawnSqr = (double) cat.getDespawnDistance() * cat.getDespawnDistance();
                double distSqr = Math.min(playerDistSqr, despawnSqr); // far players count as "in range"
                if (!(level.isLoadedAndInBounds(pos) && rightDistance(level, chunk, pos, playerDistSqr))) continue;

                if (currentSpawnData == null) {
                    @SuppressWarnings("unchecked")
                    Optional<MobSpawnSettings.SpawnerData> next = (Optional<MobSpawnSettings.SpawnerData>)
                            getRandomSpawnMobAt.invoke(level, structureManager, generator, cat, level.getRandom(), (BlockPos) pos);
                    if (next.isEmpty()) break;
                    currentSpawnData = next.get();
                    max = currentSpawnData.count().sample(level.getRandom());
                }

                int status = validPosition(level, cat, structureManager, generator, currentSpawnData, pos, distSqr);
                if (status == ABORT) return;
                if (status == SUCCESS && spawnCostOk(cx, currentSpawnData.type(), pos, chunk)) {
                    Mob mob = (Mob) getMobForSpawn.invoke(level, currentSpawnData.type());
                    if (mob == null) return;
                    mob.snapTo(xx, yStart, zz, level.getRandom().nextFloat() * 360.0F, 0.0F);
                    if (validForMob(level, mob, distSqr)) {
                        groupData = mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                                EntitySpawnReason.NATURAL, groupData);
                        level.addFreshEntityWithPassengers(mob,
                                (mob instanceof net.minecraft.world.entity.animal.feline.Ocelot
                                        && !((org.bukkit.entity.Ageable) mob.getBukkitEntity()).isAdult())
                                        ? CreatureSpawnEvent.SpawnReason.OCELOT_BABY : CreatureSpawnEvent.SpawnReason.NATURAL);
                        if (!mob.isRemoved()) {
                            clusterSize++;
                            groupSize++;
                            cnt[cat.ordinal()]++;
                            stateAfterSpawn.invoke(cx.state, mob, (ChunkAccess) chunk);
                            protect(mob);
                        }
                        if (clusterSize >= maxSpawns || clusterSize >= mob.getMaxSpawnClusterSize()) return;
                        if (mob.isMaxGroupSizeReached(groupSize)) break;
                    }
                }
            }
        }
    }

    private boolean spawnCostOk(Ctx cx, EntityType<?> type, BlockPos pos, ChunkAccess chunk) throws Throwable {
        return (boolean) stateCanSpawn.invoke(cx.state, type, (Level) cx.level, pos, chunk);
    }

    /** {@code isRightDistanceToPlayerAndSpawnPoint}: 24 blocks from a player and from the world spawn. */
    private boolean rightDistance(ServerLevel level, ChunkAccess chunk, BlockPos.MutableBlockPos pos, double playerDistSqr) {
        if (playerDistSqr <= 576.0) return false;
        var respawn = level.getRespawnData();
        if (respawn.dimension() == level.dimension()
                && respawn.pos().closerToCenterThan(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), 24.0)) {
            return false;
        }
        ChunkPos chunkPos = ChunkPos.containing(pos);
        return chunkPos.equals(chunk.getPos()) || level.canSpawnEntitiesInChunk(chunkPos);
    }

    private static final int FAIL = 0, SUCCESS = 1, CANCELLED = 2, ABORT = 3;

    /** {@code isValidSpawnPostitionForType} with Paper's PreCreatureSpawnEvent. */
    private int validPosition(ServerLevel level, MobCategory cat, StructureManager sm, ChunkGenerator gen,
                              MobSpawnSettings.SpawnerData data, BlockPos.MutableBlockPos pos, double distSqr) throws Throwable {
        EntityType<?> type = data.type();
        var event = new com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent(
                CraftLocation.toBukkit(pos, level), CraftEntityType.minecraftToBukkit(type), CreatureSpawnEvent.SpawnReason.NATURAL);
        if (!event.callEvent()) return event.shouldAbortSpawn() ? ABORT : CANCELLED;
        boolean ok = type.getCategory() != MobCategory.MISC
                && (type.canSpawnFarFromPlayer() || !(distSqr > (double) type.getCategory().getDespawnDistance() * type.getCategory().getDespawnDistance()))
                && type.canSummon()
                && (boolean) canSpawnMobAt.invoke(level, sm, gen, cat, data, (BlockPos) pos)
                && SpawnPlacements.isSpawnPositionOk(type, level, pos)
                && SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, pos, level.getRandom())
                && level.noCollision(type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        return ok ? SUCCESS : FAIL;
    }

    /** {@code isValidPositionForMob}. */
    private boolean validForMob(ServerLevel level, Mob mob, double distSqr) {
        double d = mob.getType().getCategory().getDespawnDistance();
        return (!(distSqr > d * d) || !mob.removeWhenFarAway(distSqr))
                && mob.checkSpawnRules(level, EntitySpawnReason.NATURAL)
                && mob.checkSpawnObstruction(level);
    }
}
