package sculk.core.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * One line for the player's chat: plain text, some of it buttons that run a
 * command when clicked. Platforms turn it into the game's chat component;
 * where there is nothing to click (a server console, a log), {@link #plain}
 * stands in.
 */
public record ChatLine(List<Part> parts) {
    /** Some text: a button when {@code command} is set (with a leading slash), {@code hover} its tooltip. */
    public record Part(String text, String command, String hover) {
        public boolean button() {
            return command != null;
        }
    }

    public ChatLine {
        parts = List.copyOf(parts);
    }

    public static ChatLine of(String text) {
        return new ChatLine(List.of(new Part(text, null, null)));
    }

    /** This line with {@code text} after it. */
    public ChatLine text(String text) {
        return with(new Part(text, null, null));
    }

    /** This line with a button after it, shown as {@code [label]}, that runs {@code command}. */
    public ChatLine button(String label, String command, String hover) {
        return with(new Part(label, command, hover));
    }

    /** As plain text, for consoles and logs: buttons in brackets. */
    public String plain() {
        StringBuilder out = new StringBuilder();
        for (Part p : parts) out.append(p.button() ? "[" + p.text() + "]" : p.text());
        return out.toString();
    }

    private ChatLine with(Part part) {
        List<Part> more = new ArrayList<>(parts);
        more.add(part);
        return new ChatLine(more);
    }
}
