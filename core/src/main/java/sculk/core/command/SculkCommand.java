package sculk.core.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import sculk.core.Sculk;
import sculk.core.control.ControlServer;
import sculk.core.script.ScriptManager;

/**
 * The in-game {@code /sculk} command, generic over the command source so
 * every platform registers the same tree with its own source type (see
 * {@link sculk.core.platform.Platform#registerCommands}):
 * <ul>
 *   <li>{@code /sculk mark <description>}: records a {@code mark} event (see
 *       {@link Sculk#mark}), to find a moment in play among the packets;</li>
 *   <li>{@code /sculk script list}, {@code run <name>}, {@code stop <name>}
 *       and {@code reload}: the installed scripts, as the web UI's scripts
 *       page drives them;</li>
 *   <li>{@code /sculk ui} (game clients only): opens the web UI in the
 *       browser, signed in. The token goes straight to the browser, never
 *       into chat, where logs, screenshots and streams would keep it.</li>
 * </ul>
 */
public final class SculkCommand {
    /** The permission level a dedicated server requires: 2, the game's own level for cheat commands. */
    public static final int OPERATOR_LEVEL = 2;

    /** How a platform answers the player who ran the command. */
    public interface Reply<S> {
        void ok(S source, String message);

        void fail(S source, String message);

        static <S> Reply<S> of(BiConsumer<S, String> ok, BiConsumer<S, String> fail) {
            return new Reply<>() {
                @Override
                public void ok(S source, String message) {
                    ok.accept(source, message);
                }

                @Override
                public void fail(S source, String message) {
                    fail.accept(source, message);
                }
            };
        }
    }

    private SculkCommand() {}

    /**
     * The command tree. {@code requires} gates all of it (for example to
     * operators on a server); {@code openUrl} opens a link in the player's
     * browser, and is null where there is no player at the keyboard (a
     * dedicated server), which leaves out {@code /sculk ui}.
     */
    public static <S> LiteralArgumentBuilder<S> tree(Predicate<S> requires, Reply<S> reply, Consumer<String> openUrl) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal("sculk").requires(requires)
                .then(LiteralArgumentBuilder.<S>literal("mark")
                        .then(RequiredArgumentBuilder.<S, String>argument("description", greedyString())
                                .executes(ctx -> mark(ctx.getSource(), getString(ctx, "description"), reply))))
                .then(LiteralArgumentBuilder.<S>literal("script")
                        .then(LiteralArgumentBuilder.<S>literal("list")
                                .executes(ctx -> withScripts(ctx.getSource(), reply, s -> list(ctx.getSource(), s, reply))))
                        .then(LiteralArgumentBuilder.<S>literal("run")
                                .then(SculkCommand.<S>scriptName(SculkCommand::installedNames)
                                        .executes(ctx -> withScripts(ctx.getSource(), reply,
                                                s -> run(ctx.getSource(), s, getString(ctx, "name"), reply)))))
                        .then(LiteralArgumentBuilder.<S>literal("stop")
                                .then(SculkCommand.<S>scriptName(ScriptManager::runningNames)
                                        .executes(ctx -> withScripts(ctx.getSource(), reply,
                                                s -> stop(ctx.getSource(), s, getString(ctx, "name"), reply)))))
                        .then(LiteralArgumentBuilder.<S>literal("reload")
                                .executes(ctx -> withScripts(ctx.getSource(), reply, s -> reload(ctx.getSource(), reply)))));
        if (openUrl != null) {
            root.then(LiteralArgumentBuilder.<S>literal("ui").executes(ctx -> ui(ctx.getSource(), reply, openUrl)));
        }
        return root;
    }

    // ---- mark ----

    private static <S> int mark(S source, String description, Reply<S> reply) {
        Sculk sculk = Sculk.get();
        if (sculk == null) {
            reply.fail(source, "sculk is not running");
            return 0;
        }
        long id = sculk.mark(description, "command");
        if (id == 0) {
            reply.fail(source, "sculk is not recording events (set sculk.eventLog or sculk.control); the mark is only in the game log");
            return 0;
        }
        reply.ok(source, "marked #" + id + ": " + description);
        return 1;
    }

    // ---- scripts ----

    /** A script name: the rest of the line, since names may hold spaces; completes from {@code names}. */
    private static <S> RequiredArgumentBuilder<S, String> scriptName(Function<ScriptManager, List<String>> names) {
        SuggestionProvider<S> suggest = (ctx, builder) -> {
            Sculk sculk = Sculk.get();
            if (sculk != null && sculk.scripts() != null) {
                String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String name : names.apply(sculk.scripts())) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
        return RequiredArgumentBuilder.<S, String>argument("name", greedyString()).suggests(suggest);
    }

    private static List<String> installedNames(ScriptManager scripts) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : scripts.list()) out.add(String.valueOf(m.get("name")));
        return out;
    }

    /** Runs {@code action} with the script manager, answering its refusals (no such script, ...) as failures. */
    private static <S> int withScripts(S source, Reply<S> reply, Function<ScriptManager, Integer> action) {
        Sculk sculk = Sculk.get();
        if (sculk == null || sculk.scripts() == null) {
            reply.fail(source, "sculk is not running");
            return 0;
        }
        try {
            return action.apply(sculk.scripts());
        } catch (ScriptManager.ScriptError e) {
            reply.fail(source, e.getMessage());
            return 0;
        }
    }

    private static <S> int list(S source, ScriptManager scripts, Reply<S> reply) {
        List<Map<String, Object>> all = scripts.list();
        if (all.isEmpty()) {
            reply.ok(source, "no scripts in " + scripts.dir());
            return 0;
        }
        reply.ok(source, all.size() + (all.size() == 1 ? " script" : " scripts") + " in " + scripts.dir() + ":");
        for (Map<String, Object> m : all) reply.ok(source, "  " + m.get("name") + ": " + describe(m));
        return all.size();
    }

    /** A script's state in a few words, as the web UI's script list shows it. */
    private static String describe(Map<String, Object> script) {
        Object status = script.get("status");
        String state = status instanceof Map<?, ?> s && s.get("state") != null ? String.valueOf(s.get("state")) : "not started";
        if (status instanceof Map<?, ?> s && "failed".equals(state) && s.get("error") != null) state += " (" + s.get("error") + ")";
        List<String> notes = new ArrayList<>();
        if (Boolean.FALSE.equals(script.get("enabled"))) notes.add("disabled");
        if (script.get("skip") != null) notes.add("skipped: " + script.get("skip"));
        return notes.isEmpty() ? state : state + ", " + String.join(", ", notes);
    }

    private static <S> int run(S source, ScriptManager scripts, String name, Reply<S> reply) {
        Map<String, Object> r = scripts.run(name);
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            reply.fail(source, name + ": " + r.get("error"));
            return 0;
        }
        Object result = r.get("result");
        reply.ok(source, "started " + name + (result == null ? "" : " → " + result));
        return 1;
    }

    private static <S> int stop(S source, ScriptManager scripts, String name, Reply<S> reply) {
        scripts.stop(name);
        reply.ok(source, "stopped " + name);
        return 1;
    }

    private static <S> int reload(S source, Reply<S> reply) {
        Map<String, Object> r = Sculk.get().reloadScripts();
        Object loaded = r.get("loaded");
        String names = loaded instanceof List<?> l && !l.isEmpty()
                ? String.join(", ", l.stream().map(String::valueOf).toList()) : "no scripts";
        reply.ok(source, "reloaded: " + names);
        if (r.get("errors") instanceof List<?> errors) {
            for (Object e : errors) {
                if (e instanceof Map<?, ?> m) reply.fail(source, m.get("script") + ": " + m.get("error"));
            }
        }
        return Boolean.TRUE.equals(r.get("ok")) ? 1 : 0;
    }

    // ---- ui ----

    private static <S> int ui(S source, Reply<S> reply, Consumer<String> openUrl) {
        Sculk sculk = Sculk.get();
        ControlServer control = sculk == null ? null : sculk.control();
        if (control == null) {
            reply.fail(source, "the control server is off; start the game with -Dsculk.control=0");
            return 0;
        }
        openUrl.accept(control.uiUrl());
        reply.ok(source, "opening the web UI in your browser (" + control.url() + "/ui/)");
        return 1;
    }
}
