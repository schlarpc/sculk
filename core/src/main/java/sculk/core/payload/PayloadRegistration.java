package sculk.core.payload;

import java.util.List;

/**
 * A mod payload type as the loader registered it, captured by a platform
 * mixin at registration time.
 *
 * @param channel       payload channel id, for example {@code "appleskin:exhaustion_sync"}
 * @param protocol      protocol state the registration applies to, or {@code "*"}
 * @param flow          {@code "clientbound"}, {@code "serverbound"} or {@code "*"}
 * @param discriminator Forge SimpleChannel message index, or null
 * @param messageClass  payload class name if the registration names one, or null
 * @param codec         the mod's codec object (loader specific), for platforms that need it
 * @param callers       classes on the registration call stack, innermost first, used to
 *                      attribute registrations made through framework mods (e.g. Balm)
 */
public record PayloadRegistration(
        String channel,
        String protocol,
        String flow,
        Integer discriminator,
        String messageClass,
        Object codec,
        List<Class<?>> callers) {}
