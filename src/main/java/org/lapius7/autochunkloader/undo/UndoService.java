package org.lapius7.autochunkloader.undo;

import org.lapius7.autochunkloader.AutoChunkLoaderPlugin;
import org.lapius7.autochunkloader.loader.GroupManager;
import org.lapius7.autochunkloader.model.ChunkGroup;

import java.time.Instant;
import java.util.*;

/**
 * Per-player undo / redo of changes to groups. In memory only.
 * Each entry stores the before and after state of every group a command touched, so undo restores the
 * complete group (chunks, owner, created, spawn, randomtick) and refuses when something else changed it since.
 */
public final class UndoService {
    /** Full state of one group. */
    public record Snap(String name, String world, Set<Long> chunks, UUID owner, Instant created, boolean spawn, boolean randomTick) {
        static Snap of(ChunkGroup g) {
            return new Snap(g.name(), g.world(), Set.copyOf(g.chunks()), g.owner(), g.created(), g.spawn(), g.randomTick());
        }
    }

    private record Change(String key, Snap before, Snap after) { }

    private record Entry(int id, String label, List<Change> changes) { }

    private static final class Hist {
        final Deque<Entry> undo = new ArrayDeque<>();
        final Deque<Entry> redo = new ArrayDeque<>();
    }

    /** Thrown with a message key when an undo/redo cannot be done. */
    public static final class Refused extends Exception {
        public final String key;
        public final String arg;
        Refused(String key, String arg) { super(key); this.key = key; this.arg = arg; }
    }

    /** Result of a successful undo/redo or recorded change. */
    public record Done(int id, String label, int remaining) { }

    private final AutoChunkLoaderPlugin plugin;
    private final GroupManager mgr;
    private final Map<UUID, Hist> hist = new HashMap<>();
    private int seq;

    public UndoService(AutoChunkLoaderPlugin plugin, GroupManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    public int limit() { return Math.max(0, plugin.getConfig().getInt("undo.limit", 20)); }

    public void forget(UUID u) { hist.remove(u); }

    public Map<String, Snap> capture() {
        Map<String, Snap> m = new HashMap<>();
        for (ChunkGroup g : mgr.all()) m.put(g.name().toLowerCase(Locale.ROOT), Snap.of(g));
        return m;
    }

    /** Compare against {@code before}; store an entry if anything changed. Returns it (null when nothing changed). */
    public Done record(UUID u, String label, Map<String, Snap> before) {
        int lim = limit();
        if (lim == 0) return null;
        Map<String, Snap> after = capture();
        List<Change> ch = new ArrayList<>();
        Set<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            Snap b = before.get(k), a = after.get(k);
            if (!Objects.equals(b, a)) ch.add(new Change(k, b, a));
        }
        if (ch.isEmpty()) return null;
        Hist h = hist.computeIfAbsent(u, x -> new Hist());
        Entry e = new Entry(++seq, label, ch);
        h.undo.push(e);
        h.redo.clear();
        while (h.undo.size() > lim) h.undo.removeLast();
        return new Done(e.id, label, h.undo.size());
    }

    /** @param id when non-null, must be the newest entry (guards stale chat buttons) */
    public Done undo(UUID u, Integer id) throws Refused {
        Hist h = hist.get(u);
        if (h == null || h.undo.isEmpty()) throw new Refused("err.undo-empty", null);
        Entry e = h.undo.peek();
        if (id != null && id != e.id) throw new Refused("err.undo-stale", null);
        apply(e, true);
        h.undo.pop();
        h.redo.push(e);
        return new Done(e.id, e.label, h.undo.size());
    }

    public Done redo(UUID u, Integer id) throws Refused {
        Hist h = hist.get(u);
        if (h == null || h.redo.isEmpty()) throw new Refused("err.redo-empty", null);
        Entry e = h.redo.peek();
        if (id != null && id != e.id) throw new Refused("err.undo-stale", null);
        apply(e, false);
        h.redo.pop();
        h.undo.push(e);
        return new Done(e.id, e.label, h.redo.size());
    }

    public int undoSize(UUID u) { Hist h = hist.get(u); return h == null ? 0 : h.undo.size(); }
    public int redoSize(UUID u) { Hist h = hist.get(u); return h == null ? 0 : h.redo.size(); }

    private void apply(Entry e, boolean undo) throws Refused {
        // 1. verify every group is still exactly as the operation left it
        for (Change c : e.changes) {
            Snap expect = undo ? c.after : c.before;
            ChunkGroup cur = mgr.get(c.key);
            Snap now = cur == null ? null : Snap.of(cur);
            if (!Objects.equals(now, expect)) {
                String n = expect != null ? expect.name() : (undo ? c.before : c.after).name();
                throw new Refused(expect == null ? "err.undo-exists" : cur == null ? "err.undo-gone" : "err.undo-changed", n);
            }
        }
        // 2. removals first (so a rename frees its name), then restore / replace
        for (Change c : e.changes) {
            Snap target = undo ? c.before : c.after;
            ChunkGroup cur = mgr.get(c.key);
            if (cur == null) continue;
            if (target != null && target.name().equals(cur.name()) && target.chunks().equals(cur.chunks())) continue; // flags only: below
            mgr.remove(cur);
        }
        boolean flags = false;
        for (Change c : e.changes) {
            Snap target = undo ? c.before : c.after;
            if (target == null) continue;
            ChunkGroup cur = mgr.get(c.key);
            if (cur != null) { // same name and chunks: only the switches differ
                cur.spawn(target.spawn());
                cur.randomTick(target.randomTick());
                flags = true;
            } else {
                ChunkGroup g = new ChunkGroup(target.name(), target.world(), target.chunks(), target.owner(), target.created());
                g.spawn(target.spawn());
                g.randomTick(target.randomTick());
                mgr.add(g);
            }
        }
        if (flags) mgr.touch();
    }
}
