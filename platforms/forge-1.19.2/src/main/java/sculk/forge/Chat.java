package sculk.forge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import sculk.core.platform.ChatLine;

/** Turns a {@link ChatLine} into the game's chat component (1.20.1: click and hover events take an action). */
final class Chat {
    private Chat() {}

    static MutableComponent render(ChatLine line) {
        MutableComponent out = Component.empty();
        for (ChatLine.Part part : line.parts()) {
            if (!part.button()) {
                out.append(Component.literal(part.text()));
                continue;
            }
            Style style = Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, part.command()));
            if (part.hover() != null) style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(part.hover())));
            out.append(Component.literal("[" + part.text() + "]").withStyle(style));
        }
        return out;
    }

    /** A line for the player's own chat: tagged as sculk's. */
    static Component tagged(ChatLine line) {
        return Component.literal("[sculk] ").withStyle(ChatFormatting.DARK_AQUA).append(render(line).withStyle(ChatFormatting.WHITE));
    }
}
