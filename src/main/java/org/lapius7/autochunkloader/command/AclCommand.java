package org.lapius7.autochunkloader.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.lapius7.autochunkloader.AutoChunkLoaderPlugin;
import org.lapius7.autochunkloader.inspector.Inspector;
import org.lapius7.autochunkloader.lang.Messages;
import org.lapius7.autochunkloader.loader.GroupManager;
import org.lapius7.autochunkloader.model.ChunkGroup;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

import static org.lapius7.autochunkloader.lang.Messages.c;
import static org.lapius7.autochunkloader.lang.Messages.p;

/** /acl and /autochunkloader. */
public final class AclCommand implements CommandExecutor, TabCompleter {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern HAS_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final int MAX_CHUNK_COORD = 1_875_000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final List<String> SUBS = List.of("help", "add", "here", "remove", "list", "info", "tp", "resize",
            "expand", "rename", "pos1", "pos2", "inspector", "i", "reload");

    private record Sel(String world, int x, int z) { }

    private final AutoChunkLoaderPlugin plugin;
    private final GroupManager mgr;
    private final Inspector inspector;
    private final Map<UUID, Sel> pos1 = new HashMap<>();
    private final Map<UUID, Sel> pos2 = new HashMap<>();

    /** Thrown to abort a command with a prepared error message. */
    private static final class Fail extends RuntimeException {
        final Component msg;
        Fail(Component msg) { super(null, null, false, false); this.msg = msg; }
    }

    public AclCommand(AutoChunkLoaderPlugin plugin, GroupManager mgr, Inspector inspector) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.inspector = inspector;
    }

    private Messages m() { return plugin.messages(); }
    private Fail fail(String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... r) { return new Fail(m().err(key, r)); }

    public void forget(UUID u) { pos1.remove(u); pos2.remove(u); }

    // =================================================================== execute
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        try {
            String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
            String[] r = a.length == 0 ? a : Arrays.copyOfRange(a, 1, a.length);
            switch (sub) {
                case "help", "?" -> help(s);
                case "add" -> { need(s, "manage"); add(s, r); }
                case "here", "h" -> here(s);
                case "remove", "rm", "delete", "del" -> { need(s, "manage"); remove(s, r); }
                case "list", "ls" -> list(s, r);
                case "info" -> info(s, r);
                case "tp" -> { need(s, "tp"); tp(s, r); }
                case "resize" -> { need(s, "manage"); resize(s, r); }
                case "expand" -> { need(s, "manage"); expand(s, r); }
                case "rename" -> { need(s, "manage"); rename(s, r); }
                case "pos1", "pos2" -> { need(s, "manage"); pos(s, sub.equals("pos1")); }
                case "inspector", "i" -> toggleInspector(s, r);
                case "reload" -> { need(s, "admin"); reload(s); }
                default -> s.sendMessage(m().err("err.unknown-sub", p("sub", a[0]))
                        .append(Component.space()).append(m().button("btn.help", "hover.help", "/acl help", true)));
            }
        } catch (Fail f) {
            s.sendMessage(f.msg);
        }
        return true;
    }

    // =================================================================== helpers
    private boolean has(CommandSender s, String perm) { return s.hasPermission("autochunkloader." + perm); }
    private boolean isAdmin(CommandSender s) { return has(s, "admin"); }

    private void need(CommandSender s, String perm) {
        if (!has(s, perm) && !isAdmin(s)) throw fail("err.no-permission");
    }

    private Player player(CommandSender s) {
        if (s instanceof Player pl) return pl;
        throw fail("err.player-only");
    }

    private ChunkGroup group(String name) {
        ChunkGroup g = mgr.get(name);
        if (g == null) throw fail("err.no-such-group", p("name", name));
        return g;
    }

    private ChunkGroup ownGroup(CommandSender s, String name) {
        ChunkGroup g = group(name);
        checkOwner(s, g);
        return g;
    }

    private void checkOwner(CommandSender s, ChunkGroup g) {
        if (isAdmin(s)) return;
        if (s instanceof Player pl && pl.getUniqueId().equals(g.owner())) return;
        throw fail("err.not-owner", p("name", g.name()));
    }

    private static int cx(Location l) { return Math.floorDiv(l.getBlockX(), 16); }
    private static int cz(Location l) { return Math.floorDiv(l.getBlockZ(), 16); }

    private String ownerName(ChunkGroup g) {
        if (g.owner().equals(ChunkGroup.NO_OWNER)) return m().raw("owner.console");
        OfflinePlayer op = Bukkit.getOfflinePlayer(g.owner());
        return op.getName() != null ? op.getName() : g.owner().toString().substring(0, 8);
    }

    private int parseInt(String s, String what) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { throw fail("err.not-a-number", p("value", s), p("what", what)); }
    }

    /** Integer with optional '~' / '~N' relative to {@code base}. */
    private int coord(CommandSender s, String arg, int base, boolean hasBase, String what) {
        if (arg.startsWith("~")) {
            if (!hasBase) throw fail("err.relative-needs-player");
            return base + (arg.length() == 1 ? 0 : parseInt(arg.substring(1), what));
        }
        return parseInt(arg, what);
    }

    private void checkName(String name) {
        if (!NAME.matcher(name).matches() || !HAS_LETTER.matcher(name).matches()) throw fail("err.bad-name");
        if (mgr.get(name) != null) throw fail("err.name-taken", p("name", name));
        if (SUBS.contains(name.toLowerCase(Locale.ROOT)) || name.equalsIgnoreCase("sel"))
            throw fail("err.reserved-name", p("name", name));
    }

    private World world(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) throw fail("err.no-such-world", p("world", name));
        return w;
    }

    private int pageSize() { return Math.max(3, plugin.getConfig().getInt("list.page-size", 8)); }

    private Component prefixed(Component body) { return Component.empty().append(m().text("prefix")).append(body); }

    private Component rule(Component title) {
        return m().text("ui.rule", c("title", title));
    }

    private Component rule() { return m().text("ui.rule-end"); }

    private String levelName(Chunk.LoadLevel l) { return m().raw("level." + l.name().toLowerCase(Locale.ROOT)); }

    // =================================================================== add
    private void add(CommandSender s, String[] a) {
        UUID owner = s instanceof Player pl ? pl.getUniqueId() : ChunkGroup.NO_OWNER;
        Player pl = s instanceof Player p0 ? p0 : null;
        String name;
        int rest;           // index of the first geometry arg
        if (a.length == 0) {
            name = null; rest = 0;
        } else if (a[0].chars().allMatch(Character::isDigit)) {   // "/acl add 2": radius only, auto name
            name = null; rest = 0;
        } else {
            name = a[0]; rest = 1;
        }
        String[] g = Arrays.copyOfRange(a, rest, a.length);

        String world;
        int x1, z1, x2, z2;
        boolean radiusForm = true;
        switch (g.length) {
            case 0, 1 -> {                                   // here [radius] | sel
                if (g.length == 1 && (g[0].equalsIgnoreCase("sel") || g[0].equalsIgnoreCase("selection"))) {
                    Player p = player(s);
                    Sel a1 = pos1.get(p.getUniqueId()), a2 = pos2.get(p.getUniqueId());
                    if (a1 == null || a2 == null) throw fail("err.sel-missing");
                    if (!a1.world.equals(a2.world)) throw fail("err.sel-world");
                    world = a1.world; x1 = a1.x; z1 = a1.z; x2 = a2.x; z2 = a2.z; radiusForm = false;
                } else {
                    Player p = player(s);
                    int r = g.length == 1 ? parseInt(g[0], "radius") : 0;
                    world = p.getWorld().getName();
                    x1 = cx(p.getLocation()) - r; x2 = cx(p.getLocation()) + r;
                    z1 = cz(p.getLocation()) - r; z2 = cz(p.getLocation()) + r;
                    checkRadius(s, r);
                }
            }
            case 2, 3, 4 -> {                                // x z [radius] [world]
                int bx = pl != null ? cx(pl.getLocation()) : 0, bz = pl != null ? cz(pl.getLocation()) : 0;
                int x = coord(s, g[0], bx, pl != null, "x"), z = coord(s, g[1], bz, pl != null, "z");
                int r = g.length >= 3 ? parseInt(g[2], "radius") : 0;
                if (g.length == 4) world = g[3];
                else if (pl != null) world = pl.getWorld().getName();
                else throw fail("err.console-world");
                checkRadius(s, r);
                x1 = x - r; x2 = x + r; z1 = z - r; z2 = z + r;
            }
            case 6, 7 -> {                                   // x1 y1 z1 x2 y2 z2 [world]  (block coordinates, y ignored)
                Location l = pl != null ? pl.getLocation() : null;
                int bx = l != null ? l.getBlockX() : 0, bz = l != null ? l.getBlockZ() : 0, by = l != null ? l.getBlockY() : 0;
                int ax = coord(s, g[0], bx, pl != null, "x1");
                coord(s, g[1], by, pl != null, "y1");
                int az = coord(s, g[2], bz, pl != null, "z1");
                int bx2 = coord(s, g[3], bx, pl != null, "x2");
                coord(s, g[4], by, pl != null, "y2");
                int bz2 = coord(s, g[5], bz, pl != null, "z2");
                if (g.length == 7) world = g[6];
                else if (pl != null) world = pl.getWorld().getName();
                else throw fail("err.console-world");
                x1 = Math.floorDiv(ax, 16); z1 = Math.floorDiv(az, 16);
                x2 = Math.floorDiv(bx2, 16); z2 = Math.floorDiv(bz2, 16);
                radiusForm = false;
            }
            default -> throw fail("err.add-usage");
        }

        world(world);
        if (Math.max(Math.max(Math.abs(x1), Math.abs(x2)), Math.max(Math.abs(z1), Math.abs(z2))) > MAX_CHUNK_COORD)
            throw fail("err.out-of-range");
        if (name == null) name = autoName(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
        else checkName(name);

        ChunkGroup grp = new ChunkGroup(name, world, x1, z1, x2, z2, owner, null);
        checkLimits(s, grp, null);
        mgr.add(grp);
        s.sendMessage(m().ok("ok.added", p("name", grp.name()), p("chunks", grp.chunkCount()), p("world", world))
                .append(Component.space()).append(m().button("btn.info", "hover.info", "/acl info " + grp.name(), true)));
    }

    private String autoName(int x1, int z1, int x2, int z2) {
        String base = (x1 == x2 && z1 == z2) ? "chunk-" + x1 + "_" + z1 : "area-" + x1 + "_" + z1;
        String n = base;
        for (int i = 2; mgr.get(n) != null; i++) n = base + "-" + i;
        return n;
    }

    private void checkRadius(CommandSender s, int r) {
        if (r < 0) throw fail("err.negative");
        int max = plugin.getConfig().getInt("limits.max-radius", 4);
        if (r > max && !isAdmin(s)) throw fail("err.radius-limit", p("max", max));
    }

    /** Group-size, total, and per-player limits. {@code old} is the existing group when resizing. */
    private void checkLimits(CommandSender s, ChunkGroup g, ChunkGroup old) {
        if (isAdmin(s)) return;
        var cfg = plugin.getConfig();
        int perGroup = cfg.getInt("limits.max-chunks-per-group", 100);
        if (g.chunkCount() > perGroup) throw fail("err.group-limit", p("max", perGroup), p("chunks", g.chunkCount()));
        int total = cfg.getInt("limits.max-chunks-total", 200);
        int after = mgr.totalChunks() - (old != null ? old.chunkCount() : 0) + g.chunkCount();
        if (after > total) throw fail("err.total-limit", p("max", total), p("now", mgr.totalChunks()));
        if (old == null && s instanceof Player pl) {
            int maxG = cfg.getInt("limits.max-groups-per-player", 5);
            if (mgr.countOwnedBy(pl.getUniqueId()) >= maxG) throw fail("err.player-limit", p("max", maxG));
        }
    }

    // =================================================================== here
    private void here(CommandSender s) {
        Player p = player(s);
        int x = cx(p.getLocation()), z = cz(p.getLocation());
        World w = p.getWorld();
        List<ChunkGroup> gs = mgr.groupsAt(w.getName(), x, z);
        Chunk ch = w.isChunkLoaded(x, z) ? w.getChunkAt(x, z, false) : null;

        List<Component> lines = new ArrayList<>();
        lines.add(rule(m().text("here.title", p("x", x), p("z", z))));
        lines.add(m().text("row", p("k", m().raw("lbl.world")), c("v", Component.text(w.getName()))));
        lines.add(m().text("row", p("k", m().raw("lbl.block-range")),
                c("v", Component.text((x * 16) + ".." + (x * 16 + 15) + " / " + (z * 16) + ".." + (z * 16 + 15)))));
        if (ch != null) {
            lines.add(m().text("row", p("k", m().raw("lbl.level")), c("v", levelComp(ch.getLoadLevel()))));
            Collection<org.bukkit.plugin.Plugin> holders = ch.getPluginChunkTickets();
            String h = holders.isEmpty() ? m().raw("here.no-tickets")
                    : String.join(", ", holders.stream().map(org.bukkit.plugin.Plugin::getName).toList());
            lines.add(m().text("row", p("k", m().raw("lbl.tickets")), c("v", Component.text(h))));
        } else {
            lines.add(m().text("row", p("k", m().raw("lbl.level")), c("v", levelComp(Chunk.LoadLevel.UNLOADED))));
        }
        if (gs.isEmpty()) {
            lines.add(m().text("row", p("k", m().raw("lbl.status")), c("v", m().text("here.unregistered"))));
            if (has(s, "manage") || isAdmin(s)) lines.add(Component.empty().append(m().text("ui.indent"))
                    .append(m().button("btn.add-here", "hover.add-here", "/acl add", true)));
        } else {
            lines.add(m().text("row", p("k", m().raw("lbl.status")), c("v", m().text("here.registered"))));
            for (ChunkGroup g : gs) {
                Component row = Component.empty().append(m().text("ui.indent"))
                        .append(m().text("here.group", p("name", g.name()), p("chunks", g.chunkCount())))
                        .append(Component.space()).append(m().button("btn.info", "hover.info", "/acl info " + g.name(), true))
                        .append(Component.space()).append(m().button("btn.remove", "hover.remove", "/acl remove " + g.name(), true));
                lines.add(row);
            }
        }
        lines.add(rule());
        send(s, lines);
    }

    private Component levelComp(Chunk.LoadLevel l) {
        return m().text("level.fmt." + l.name().toLowerCase(Locale.ROOT), p("level", levelName(l)));
    }

    private void send(CommandSender s, List<Component> lines) {
        s.sendMessage(Component.join(JoinConfiguration.newlines(), lines));
    }

    // =================================================================== remove
    private void remove(CommandSender s, String[] a) {
        ChunkGroup g;
        if (a.length == 0) {
            Player p = player(s);
            List<ChunkGroup> gs = mgr.groupsAt(p.getWorld().getName(), cx(p.getLocation()), cz(p.getLocation()));
            if (gs.isEmpty()) throw fail("err.not-registered-here");
            if (gs.size() > 1) {
                List<Component> parts = new ArrayList<>();
                parts.add(m().info("remove.pick"));
                for (ChunkGroup x : gs) parts.add(Component.empty().append(m().text("ui.indent"))
                        .append(m().button("btn.remove-named", "hover.remove", "/acl remove " + x.name(), true, p("name", x.name()))));
                send(s, parts);
                return;
            }
            g = gs.get(0);
        } else {
            g = group(a[0]);
        }
        checkOwner(s, g);
        boolean confirmed = a.length >= 2 && a[1].equalsIgnoreCase("confirm");
        if (g.chunkCount() > 1 && !confirmed) {
            s.sendMessage(m().info("remove.confirm", p("name", g.name()), p("chunks", g.chunkCount()))
                    .append(Component.space())
                    .append(m().button("btn.confirm", "hover.confirm", "/acl remove " + g.name() + " confirm", true)));
            return;
        }
        mgr.remove(g);
        s.sendMessage(m().ok("ok.removed", p("name", g.name()), p("chunks", g.chunkCount())));
    }

    // =================================================================== list
    private void list(CommandSender s, String[] a) {
        List<ChunkGroup> all = new ArrayList<>(mgr.all());
        if (all.isEmpty()) {
            s.sendMessage(m().info("list.empty"));
            return;
        }
        int size = pageSize();
        int pages = (all.size() + size - 1) / size;
        int page = a.length > 0 ? parseInt(a[0], "page") : 1;
        page = Math.max(1, Math.min(page, pages));
        List<Component> lines = new ArrayList<>();
        lines.add(rule(m().text("list.title", p("count", all.size()), p("chunks", mgr.totalChunks()))));
        boolean canTp = s instanceof Player && has(s, "tp");
        for (ChunkGroup g : all.subList((page - 1) * size, Math.min(all.size(), page * size))) {
            Component row = Component.empty().append(m().text("ui.indent"))
                    .append(m().text(g.active() ? "list.row" : "list.row-inactive",
                            p("name", g.name()), p("world", g.world()),
                            p("x", g.centerX()), p("z", g.centerZ()), p("chunks", g.chunkCount()),
                            p("w", g.width()), p("d", g.depth())))
                    .append(Component.space()).append(m().button("btn.info", "hover.info", "/acl info " + g.name(), true));
            if (canTp) row = row.append(Component.space()).append(m().button("btn.tp", "hover.tp", "/acl tp " + g.name(), true));
            if (canManage(s, g)) row = row.append(Component.space())
                    .append(m().button("btn.remove", "hover.remove", "/acl remove " + g.name(), true));
            lines.add(row);
        }
        Component prev = page > 1 ? m().button("btn.prev", "hover.prev", "/acl list " + (page - 1), true) : m().text("btn.prev-off");
        Component next = page < pages ? m().button("btn.next", "hover.next", "/acl list " + (page + 1), true) : m().text("btn.next-off");
        lines.add(m().text("list.footer", c("prev", prev), c("next", next), p("page", page), p("pages", pages)));
        send(s, lines);
    }

    private boolean canManage(CommandSender s, ChunkGroup g) {
        if (!(has(s, "manage") || isAdmin(s))) return false;
        return isAdmin(s) || (s instanceof Player pl && pl.getUniqueId().equals(g.owner()));
    }

    // =================================================================== info
    private void info(CommandSender s, String[] a) {
        List<ChunkGroup> gs;
        if (a.length > 0) gs = List.of(group(a[0]));
        else {
            Player p = player(s);
            gs = mgr.groupsAt(p.getWorld().getName(), cx(p.getLocation()), cz(p.getLocation()));
            if (gs.isEmpty()) throw fail("err.not-registered-here");
        }
        for (ChunkGroup g : gs) showInfo(s, g);
    }

    private void showInfo(CommandSender s, ChunkGroup g) {
        World w = Bukkit.getWorld(g.world());
        List<Component> lines = new ArrayList<>();
        lines.add(rule(m().text("info.title", p("name", g.name()))));
        lines.add(m().text("row", p("k", m().raw("lbl.world")), c("v", Component.text(g.world() + (w == null ? " " + m().raw("info.world-missing") : "")))));
        lines.add(m().text("row", p("k", m().raw("lbl.range")), c("v", m().text("info.range",
                p("x1", g.minX()), p("z1", g.minZ()), p("x2", g.maxX()), p("z2", g.maxZ()),
                p("w", g.width()), p("d", g.depth()), p("chunks", g.chunkCount())))));
        lines.add(m().text("row", p("k", m().raw("lbl.block-range")), c("v", Component.text(
                (g.minX() * 16) + ".." + (g.maxX() * 16 + 15) + " / " + (g.minZ() * 16) + ".." + (g.maxZ() * 16 + 15)))));
        lines.add(m().text("row", p("k", m().raw("lbl.center")), c("v", Component.text(g.centerX() + ", " + g.centerZ()))));
        lines.add(m().text("row", p("k", m().raw("lbl.owner")), c("v", Component.text(ownerName(g)))));
        lines.add(m().text("row", p("k", m().raw("lbl.created")), c("v", Component.text(DATE.format(g.created())))));

        if (w != null && g.active()) {
            EnumMap<Chunk.LoadLevel, Integer> count = new EnumMap<>(Chunk.LoadLevel.class);
            for (int x = g.minX(); x <= g.maxX(); x++)
                for (int z = g.minZ(); z <= g.maxZ(); z++) {
                    Chunk.LoadLevel lv = w.isChunkLoaded(x, z) ? w.getChunkAt(x, z, false).getLoadLevel() : Chunk.LoadLevel.UNLOADED;
                    count.merge(lv, 1, Integer::sum);
                }
            List<Component> parts = new ArrayList<>();
            count.forEach((lv, n) -> parts.add(m().text("info.level-count", c("level", levelComp(lv)), p("n", n), p("total", g.chunkCount()))));
            lines.add(m().text("row", p("k", m().raw("lbl.level")), c("v", Component.join(JoinConfiguration.separator(Component.text("  ")), parts))));
        } else {
            lines.add(m().text("row", p("k", m().raw("lbl.status")), c("v", m().text("info.inactive"))));
        }

        Component btns = Component.empty().append(m().text("ui.indent"));
        if (s instanceof Player && has(s, "tp")) btns = btns.append(m().button("btn.tp", "hover.tp", "/acl tp " + g.name(), true)).append(Component.space());
        if (s instanceof Player) btns = btns.append(m().button("btn.inspector", "hover.inspector", "/acl inspector", true)).append(Component.space());
        if (canManage(s, g)) {
            btns = btns.append(m().button("btn.grow", "hover.grow", "/acl resize " + g.name() + " +1", true)).append(Component.space())
                    .append(m().button("btn.shrink", "hover.shrink", "/acl resize " + g.name() + " -1", true)).append(Component.space())
                    .append(m().button("btn.rename", "hover.rename", "/acl rename " + g.name() + " ", false)).append(Component.space())
                    .append(m().button("btn.remove", "hover.remove", "/acl remove " + g.name(), true));
        }
        lines.add(btns);
        lines.add(rule());
        send(s, lines);
    }

    // =================================================================== tp
    private void tp(CommandSender s, String[] a) {
        Player p = player(s);
        if (a.length == 0) throw fail("err.usage", p("usage", "/acl tp <name>"));
        ChunkGroup g = group(a[0]);
        World w = Bukkit.getWorld(g.world());
        if (w == null) throw fail("err.no-such-world", p("world", g.world()));
        int bx = g.centerX() * 16 + 8, bz = g.centerZ() * 16 + 8;
        int y = w.getHighestBlockYAt(bx, bz) + 1;
        Location dest = new Location(w, bx + 0.5, y, bz + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch());
        p.teleportAsync(dest).thenAccept(ok -> {
            if (ok) p.sendMessage(m().ok("ok.teleported", p("name", g.name())));
            else p.sendMessage(m().err("err.tp-failed"));
        });
    }

    // =================================================================== resize / expand
    private void resize(CommandSender s, String[] a) {
        if (a.length < 2) throw fail("err.usage", p("usage", "/acl resize <name> <+n|-n>"));
        ChunkGroup g = ownGroup(s, a[0]);
        int d = parseInt(a[1].startsWith("+") ? a[1].substring(1) : a[1], "amount");
        if (d == 0) throw fail("err.negative");
        int x1 = g.minX() - d, z1 = g.minZ() - d, x2 = g.maxX() + d, z2 = g.maxZ() + d;
        if (x1 > x2 || z1 > z2) throw fail("err.too-small");
        applyBounds(s, g, x1, z1, x2, z2);
        s.sendMessage(m().ok("ok.resized", p("name", g.name()), p("w", g.width()), p("d", g.depth()), p("chunks", g.chunkCount())));
    }

    private void expand(CommandSender s, String[] a) {
        Player pl = player(s);
        ChunkGroup g;
        if (a.length > 0) g = ownGroup(s, a[0]);
        else {
            Location l = pl.getLocation();
            List<ChunkGroup> near = new ArrayList<>(mgr.all());
            near.removeIf(x -> !x.world().equals(pl.getWorld().getName()) || !canManage(s, x));
            if (near.size() != 1) throw fail("err.usage", p("usage", "/acl expand <name>"));
            g = near.get(0);
        }
        if (!g.world().equals(pl.getWorld().getName())) throw fail("err.other-world");
        int x = cx(pl.getLocation()), z = cz(pl.getLocation());
        applyBounds(s, g, Math.min(g.minX(), x), Math.min(g.minZ(), z), Math.max(g.maxX(), x), Math.max(g.maxZ(), z));
        s.sendMessage(m().ok("ok.expanded", p("name", g.name()), p("w", g.width()), p("d", g.depth()), p("chunks", g.chunkCount())));
    }

    private void applyBounds(CommandSender s, ChunkGroup g, int x1, int z1, int x2, int z2) {
        ChunkGroup probe = new ChunkGroup("probe", g.world(), x1, z1, x2, z2, g.owner(), null);
        if (Math.max(Math.max(Math.abs(x1), Math.abs(x2)), Math.max(Math.abs(z1), Math.abs(z2))) > MAX_CHUNK_COORD) throw fail("err.out-of-range");
        checkLimits(s, probe, g);
        mgr.resize(g, probe.minX(), probe.minZ(), probe.maxX(), probe.maxZ());
    }

    // =================================================================== rename
    private void rename(CommandSender s, String[] a) {
        if (a.length < 2) throw fail("err.usage", p("usage", "/acl rename <old> <new>"));
        ChunkGroup g = ownGroup(s, a[0]);
        String old = g.name();
        if (!a[1].equalsIgnoreCase(old)) checkName(a[1]);
        mgr.rename(g, a[1]);
        s.sendMessage(m().ok("ok.renamed", p("old", old), p("name", g.name())));
    }

    // =================================================================== pos1 / pos2
    private void pos(CommandSender s, boolean first) {
        Player p = player(s);
        Location l = p.getLocation();
        Sel sel = new Sel(p.getWorld().getName(), cx(l), cz(l));
        (first ? pos1 : pos2).put(p.getUniqueId(), sel);
        Sel o = (first ? pos2 : pos1).get(p.getUniqueId());
        Component msg = m().ok(first ? "ok.pos1" : "ok.pos2", p("x", sel.x), p("z", sel.z));
        if (o != null && o.world.equals(sel.world)) {
            int n = (Math.abs(sel.x - o.x) + 1) * (Math.abs(sel.z - o.z) + 1);
            msg = msg.append(Component.space()).append(m().text("pos.ready", p("chunks", n)))
                    .append(Component.space()).append(m().button("btn.add-sel", "hover.add-sel", "/acl add sel", false));
        }
        p.sendMessage(msg);
    }

    // =================================================================== inspector / reload / help
    private void toggleInspector(CommandSender s, String[] a) {
        Player p = player(s);
        boolean on = a.length > 0 ? a[0].equalsIgnoreCase("on") : !inspector.enabled(p);
        if (a.length > 0 && !a[0].equalsIgnoreCase("on") && !a[0].equalsIgnoreCase("off")) throw fail("err.usage", p("usage", "/acl inspector [on|off]"));
        inspector.set(p, on);
        p.sendMessage(m().ok(on ? "ok.inspector-on" : "ok.inspector-off"));
    }

    private void reload(CommandSender s) {
        plugin.reloadAll();
        s.sendMessage(m().ok("ok.reloaded"));
    }

    private void help(CommandSender s) {
        List<Component> lines = new ArrayList<>();
        lines.add(rule(m().text("help.title", p("version", plugin.getPluginMeta().getVersion()))));
        for (String k : new String[]{"here", "add", "add-range", "remove", "list", "info", "tp", "resize", "expand", "rename", "pos", "inspector", "reload"}) {
            String cmd = m().raw("help.cmd." + k);
            lines.add(Component.empty().append(m().text("ui.indent")).append(
                    m().text("help.row", p("cmd", cmd), p("desc", m().raw("help.desc." + k)))
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(cmd.split(" [<\\[]")[0] + " "))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(m().text("hover.suggest")))));
        }
        lines.add(rule());
        send(s, lines);
    }

    // =================================================================== tab completion
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String cur = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            for (String sub : SUBS) {
                String perm = switch (sub) {
                    case "add", "remove", "resize", "expand", "rename", "pos1", "pos2" -> "manage";
                    case "tp" -> "tp";
                    case "reload" -> "admin";
                    default -> "use";
                };
                if (has(s, perm) || isAdmin(s)) out.add(sub);
            }
            return filter(out, cur);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        int i = a.length - 1; // index of the arg being typed
        Player pl = s instanceof Player p0 ? p0 : null;
        switch (sub) {
            case "add" -> {
                if (!(has(s, "manage") || isAdmin(s))) return List.of();
                int g = i - 1; // geometry arg index after the name
                if (g == 0) out.addAll(List.of("<name>", "sel", "0", "1", "2"));
                else if (!a[1].equalsIgnoreCase("sel")) {
                    out.add("~");
                    if (pl != null) {
                        Location l = pl.getLocation();
                        // chunk form: x z [radius] [world]; block form: x1 y1 z1 x2 y2 z2 [world]
                        out.add(String.valueOf(g == 1 ? cx(l) : g == 2 ? cz(l) : 0));
                        out.add(String.valueOf(switch (g % 3) { case 1 -> l.getBlockX(); case 2 -> l.getBlockY(); default -> l.getBlockZ(); }));
                    }
                    if (g >= 3) out.addAll(List.of("0", "1", "2"));
                    if (g >= 3) out.addAll(worlds());
                }
            }
            case "remove", "rm", "delete", "del" -> {
                if (i == 1) out.addAll(names(s, true));
                else if (i == 2) out.add("confirm");
            }
            case "info", "tp" -> { if (i == 1) out.addAll(names(s, false)); }
            case "resize" -> {
                if (i == 1) out.addAll(names(s, true));
                else if (i == 2) out.addAll(List.of("+1", "-1", "+2", "-2"));
            }
            case "expand" -> { if (i == 1) out.addAll(names(s, true)); }
            case "rename" -> { if (i == 1) out.addAll(names(s, true)); else if (i == 2) out.add("<new-name>"); }
            case "list", "ls" -> {
                if (i == 1) {
                    int pages = Math.max(1, (mgr.all().size() + pageSize() - 1) / pageSize());
                    for (int n = 1; n <= pages && n <= 20; n++) out.add(String.valueOf(n));
                }
            }
            case "inspector", "i" -> { if (i == 1) out.addAll(List.of("on", "off")); }
            default -> { }
        }
        return filter(out, cur);
    }

    private List<String> worlds() { return Bukkit.getWorlds().stream().map(World::getName).toList(); }

    private List<String> names(CommandSender s, boolean ownOnly) {
        List<String> out = new ArrayList<>();
        for (ChunkGroup g : mgr.all()) if (!ownOnly || canManage(s, g)) out.add(g.name());
        return out;
    }

    private static List<String> filter(List<String> in, String cur) {
        List<String> out = new ArrayList<>();
        for (String x : in) if (x.toLowerCase(Locale.ROOT).startsWith(cur)) out.add(x);
        return out;
    }
}
