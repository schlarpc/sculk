package sculk.core.net;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import sculk.core.Sculk;
import sculk.core.packet.PacketEvent;
import sculk.core.platform.Side;

/**
 * One game connection (a Netty channel) and the ways to inject into it.
 *
 * <p>Injection is pure Netty, anchored on the game's codec handlers by name:
 * <ul>
 *   <li>outbound frame: written from the encoder's context, so it is
 *       compressed, length-prefixed and encrypted by the game but never
 *       re-encoded;</li>
 *   <li>outbound packet: written from the tail, so it goes through the game's
 *       encoder (and sculk's encoder hook) like any other packet;</li>
 *   <li>inbound frame: fired into the decoder, as if it had just arrived;</li>
 *   <li>inbound packet: fired from the decoder's context, straight to the
 *       game's handlers, as if it had just been decoded.</li>
 * </ul>
 * All injection runs on the channel's event loop.
 */
public final class SculkConnection {
    private static final AttributeKey<SculkConnection> KEY = AttributeKey.valueOf("sculk:connection");
    private static final AtomicInteger IDS = new AtomicInteger();

    /** Handler names the game has used for its codecs; the *_config names appear between protocol states. */
    private static final List<String> DECODER_NAMES = List.of("decoder", "inbound_config");
    private static final List<String> ENCODER_NAMES = List.of("encoder", "outbound_config");

    private final int id = IDS.incrementAndGet();
    private final Channel channel;
    private final Side side;
    private final Set<Object> injectedOutbound = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private boolean injectingInbound;
    private volatile Object decoderCodec;
    private volatile Object encoderCodec;

    private SculkConnection(Channel channel, Side side) {
        this.channel = channel;
        this.side = side;
    }

    /** Returns the connection for {@code channel}, creating it if this is the first sighting. */
    public static SculkConnection of(Channel channel, Side side) {
        SculkConnection existing = channel.attr(KEY).get();
        if (existing != null) return existing;
        SculkConnection created = new SculkConnection(channel, side);
        SculkConnection raced = channel.attr(KEY).setIfAbsent(created);
        return raced != null ? raced : created;
    }

    /** Returns the connection for {@code channel}, or null if sculk has not seen it. */
    public static SculkConnection peek(Channel channel) {
        return channel.attr(KEY).get();
    }

    public int getId() { return id; }
    /** Which side we are; scripts see it as the string {@code side}. */
    public Side sideEnum() { return side; }
    public String getSide() { return side.id(); }
    public Channel channel() { return channel; }
    public SocketAddress getRemoteAddress() { return channel.remoteAddress(); }
    /** The peer's address as text, as events show it. */
    public String getRemote() { return String.valueOf(channel.remoteAddress()); }
    public boolean isOpen() { return channel.isOpen(); }
    /** The game's current decoder for this connection (from the pipeline), or the last one seen. */
    public Object decoderCodec() {
        Object live = channel.pipeline().get("decoder");
        return live != null ? live : decoderCodec;
    }

    /** The game's current encoder for this connection (from the pipeline), or the last one seen. */
    public Object encoderCodec() {
        Object live = channel.pipeline().get("encoder");
        return live != null ? live : encoderCodec;
    }

    public void decoderCodec(Object codec) { this.decoderCodec = codec; }
    public void encoderCodec(Object codec) { this.encoderCodec = codec; }

    /** True once for the decode call that an inbound frame injection triggered. */
    public boolean consumeInboundInjection() {
        boolean was = injectingInbound;
        injectingInbound = false;
        return was;
    }

    public boolean consumeOutboundInjection(Object packet) {
        return injectedOutbound.remove(packet);
    }

    // ---- injection: Java API ----

    public void sendFrame(Direction direction, byte[] frame) {
        byte[] copy = frame.clone();
        onLoop(() -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (direction == Direction.OUTBOUND) {
                ChannelHandlerContext encoder = first(pipeline, ENCODER_NAMES);
                encoder.writeAndFlush(Unpooled.wrappedBuffer(copy));
                Sculk.get().recordInjected(this, direction, null, copy, encoderCodec());
            } else {
                ChannelHandlerContext beforeDecoder = predecessor(pipeline, first(pipeline, DECODER_NAMES));
                injectingInbound = true;
                beforeDecoder.fireChannelRead(Unpooled.wrappedBuffer(copy));
                injectingInbound = false;
            }
        });
    }

    public void sendPacket(Direction direction, Object packet) {
        onLoop(() -> {
            if (direction == Direction.OUTBOUND) {
                injectedOutbound.add(packet);
                channel.writeAndFlush(packet);
            } else {
                ChannelHandlerContext decoder = first(channel.pipeline(), DECODER_NAMES);
                Sculk.get().recordInjected(this, direction, packet, null, decoderCodec());
                decoder.fireChannelRead(packet);
            }
        });
    }

    /** Sends a previously seen packet again, as bytes when sculk saw its frame. */
    public void replay(PacketEvent event) {
        if (event.frameBytes() != null) sendFrame(event.direction(), event.frameBytes());
        else sendPacket(event.direction(), event.packetObject());
    }

    // ---- injection: script-friendly names ----

    public void inject(String direction, byte[] frame) {
        sendFrame(parse(direction), frame);
    }

    public void injectPacket(String direction, Object packet) {
        sendPacket(parse(direction), packet);
    }

    private static Direction parse(String direction) {
        return Direction.valueOf(direction.toUpperCase(Locale.ROOT));
    }

    private void onLoop(Runnable task) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                Sculk.LOG.error("injection on connection {} failed", id, e);
            }
        };
        if (channel.eventLoop().inEventLoop()) guarded.run();
        else channel.eventLoop().execute(guarded);
    }

    private static ChannelHandlerContext first(ChannelPipeline pipeline, List<String> names) {
        for (String name : names) {
            ChannelHandlerContext ctx = pipeline.context(name);
            if (ctx != null) return ctx;
        }
        throw new IllegalStateException("no codec handler " + names + " in pipeline " + pipeline.names()
                + " (in-memory connections have no codec)");
    }

    private static ChannelHandlerContext predecessor(ChannelPipeline pipeline, ChannelHandlerContext target) {
        List<String> names = pipeline.names();
        int index = names.indexOf(target.name());
        if (index <= 0) throw new IllegalStateException("nothing precedes " + target.name() + " in " + names);
        return pipeline.context(names.get(index - 1));
    }

    /** The {@code conn} layer of events: {@code {id, side, remote}}. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("side", side.id());
        m.put("remote", getRemote());
        return m;
    }

    @Override
    public String toString() {
        return "SculkConnection#" + id + "(" + side.id() + " " + channel.remoteAddress() + ")";
    }
}
