package sculk.core.remote;

import java.util.ArrayDeque;

/**
 * A {@link Tunnel.Link} over one WebRTC data channel. Messages wait in a
 * queue of our own until str0m takes them (it refuses when its buffer is
 * full, and a refused message must not be lost), which drains whenever the
 * channel's queue runs low. A sender off the WebRtc thread waits while too
 * much is queued, which is what slows the tunnel's readers; on the WebRtc
 * thread nothing ever waits.
 */
final class ChannelLink implements Tunnel.Link {
    /** Senders wait while more than this many bytes are queued, ours and str0m's together. */
    static final int HIGH = 1 << 20;
    /** How much to hand str0m at most; its own limit is 1 MiB (rust/sculk-webrtc). */
    static final int STR0M = 768 << 10;
    /**
     * The channel reports writable once below this. It must be well under
     * what str0m holds, or the queue never rises above it to fall below.
     */
    static final int LOW = 128 << 10;

    private final WebRtc webrtc;
    private final WebRtc.Connection conn;
    private final int channel;
    // Owned by the WebRtc thread.
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private long queuedBytes;
    // Shared, under this.
    private long waitingBytes;
    private boolean closed;

    ChannelLink(WebRtc webrtc, WebRtc.Connection conn, int channel) {
        this.webrtc = webrtc;
        this.conn = conn;
        this.channel = channel;
        conn.setLowMark(channel, LOW);
    }

    @Override
    public void send(byte[] message) throws InterruptedException {
        if (webrtc.onThread()) {
            enqueue(message);
            return;
        }
        synchronized (this) {
            while (!closed && waitingBytes > HIGH) wait(1000);
            if (closed) return;
            waitingBytes += message.length;
        }
        webrtc.execute(() -> enqueue(message));
    }

    /** On the WebRtc thread. */
    private void enqueue(byte[] message) {
        queue.add(message);
        queuedBytes += message.length;
        flush();
    }

    /** On the WebRtc thread: hands str0m what it takes, then tells waiting senders. */
    void flush() {
        while (!queue.isEmpty() && conn.queued(channel) < STR0M && conn.send(channel, queue.peek(), true)) {
            queuedBytes -= queue.poll().length;
        }
        long total = queuedBytes + conn.queued(channel);
        synchronized (this) {
            waitingBytes = total;
            notifyAll();
        }
    }

    /** The connection ended: release anyone waiting. */
    void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }
}
