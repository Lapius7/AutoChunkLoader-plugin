package org.lapius7.autochunkloader.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Language files (jar defaults, overlaid by plugins/AutoChunkLoader/lang/&lt;code&gt;.yml) rendered with MiniMessage. */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] BUNDLED = {"ja", "en"};

    private final JavaPlugin plugin;
    private final Map<String, String> table = new HashMap<>();

    public Messages(JavaPlugin plugin) { this.plugin = plugin; }

    public void load(String code) {
        for (String b : BUNDLED) {
            File f = new File(plugin.getDataFolder(), "lang/" + b + ".yml");
            if (!f.exists()) plugin.saveResource("lang/" + b + ".yml", false);
        }
        table.clear();
        readBundled("en");           // base: any missing key falls back to English
        readBundled(code);           // chosen language from the jar
        readFile(new File(plugin.getDataFolder(), "lang/" + code + ".yml")); // user edits win
    }

    private void readBundled(String code) {
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) return;
            fill(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception ignored) { }
    }

    private void readFile(File f) {
        if (f.exists()) fill(YamlConfiguration.loadConfiguration(f));
    }

    private void fill(YamlConfiguration y) {
        for (String k : y.getKeys(true)) if (y.isString(k)) table.put(k, y.getString(k));
    }

    public String raw(String key) { return table.getOrDefault(key, key); }

    private Component parse(String key, TagResolver... r) {
        return MM.deserialize(raw(key), TagResolver.resolver(r));
    }

    /** Plain fragment, no prefix. */
    public Component text(String key, TagResolver... r) { return parse(key, r); }

    /** Prefixed line: info. */
    public Component info(String key, TagResolver... r) {
        return Component.empty().append(parse("prefix")).append(parse(key, r).colorIfAbsent(NamedTextColor.GRAY));
    }

    /** Prefixed success line. */
    public Component ok(String key, TagResolver... r) {
        return Component.empty().append(parse("prefix")).append(parse("mark.ok")).append(parse(key, r).colorIfAbsent(NamedTextColor.GRAY));
    }

    /** Prefixed error line. */
    public Component err(String key, TagResolver... r) {
        return Component.empty().append(parse("prefix")).append(parse("mark.err")).append(parse(key, r).colorIfAbsent(NamedTextColor.RED));
    }

    /** Clickable button with a hover description. {@code run} executes the command, otherwise it is put in the chat box. */
    public Component button(String labelKey, String hoverKey, String command, boolean run, TagResolver... r) {
        Component hover = parse(hoverKey, r).append(Component.newline())
                .append(Component.text(command, NamedTextColor.DARK_GRAY));
        return parse(labelKey, r)
                .clickEvent(run ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(hover));
    }

    public static TagResolver p(String name, String value) { return Placeholder.unparsed(name, value); }
    public static TagResolver p(String name, int value) { return Placeholder.unparsed(name, Integer.toString(value)); }
    public static TagResolver c(String name, Component value) { return Placeholder.component(name, value); }
}
