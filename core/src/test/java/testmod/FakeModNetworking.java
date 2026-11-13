package testmod;

import sculk.core.payload.PayloadRegistry;

/** Stands in for a mod's init code; lives outside sculk.* so the stack walker counts it. */
public final class FakeModNetworking {
    private FakeModNetworking() {}

    public static void register(String channel) {
        PayloadRegistry.record(channel, "play", "clientbound", null, "testmod.Msg", new Object());
    }
}
