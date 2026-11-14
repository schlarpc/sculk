package sculk.core.command;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import sculk.core.Sculk;

/**
 * The in-game {@code /sculk} command, generic over the command source so
 * every platform registers the same tree with its own source type (see
 * {@link sculk.core.platform.Platform#registerCommands}):
 * <ul>
 *   <li>{@code /sculk mark <description>}: records a {@code mark} event (see
 *       {@link Sculk#mark}), to find a moment in play among the packets.</li>
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

    /** The command tree; {@code requires} gates all of it (for example to operators on a server). */
    public static <S> LiteralArgumentBuilder<S> tree(Predicate<S> requires, Reply<S> reply) {
        return LiteralArgumentBuilder.<S>literal("sculk")
                .requires(requires)
                .then(LiteralArgumentBuilder.<S>literal("mark")
                        .then(RequiredArgumentBuilder.<S, String>argument("description", greedyString())
                                .executes(ctx -> mark(ctx.getSource(), getString(ctx, "description"), reply))));
    }

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
}
