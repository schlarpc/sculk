package sculk.core.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every mod payload registration seen in this process.
 *
 * <p>Static on purpose: loaders register payloads during mod loading, which can
 * happen before (or without) sculk's own entrypoint running.
 */
public final class PayloadRegistry {
    private static final List<PayloadRegistration> REGISTRATIONS = new CopyOnWriteArrayList<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private PayloadRegistry() {}

    /** Called from platform mixins at the point a loader registers a payload type. */
    public static void record(
            String channel, String protocol, String flow, Integer discriminator, String messageClass, Object codec) {
        List<Class<?>> callers = WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> !isInfrastructure(c.getName()))
                .distinct()
                .limit(32)
                .toList());
        REGISTRATIONS.add(new PayloadRegistration(channel, protocol, flow, discriminator, messageClass, codec, callers));
    }

    public static List<PayloadRegistration> all() {
        return new ArrayList<>(REGISTRATIONS);
    }

    public static List<PayloadRegistration> forChannel(String channel) {
        return REGISTRATIONS.stream().filter(r -> r.channel().equals(channel)).toList();
    }

    /** Frames that never identify the mod asking for a registration. */
    static boolean isInfrastructure(String className) {
        return className.startsWith("java.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("sculk.")
                || className.startsWith("net.minecraft.")
                || className.startsWith("com.mojang.")
                || className.startsWith("net.neoforged.")
                || className.startsWith("net.minecraftforge.")
                || className.startsWith("net.fabricmc.")
                || className.startsWith("cpw.mods.")
                || className.startsWith("org.spongepowered.")
                || className.startsWith("com.llamalad7.");
    }
}
