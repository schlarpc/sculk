package sculk.core.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import sculk.shaded.com.dylibso.chicory.runtime.ExportFunction;
import sculk.shaded.com.dylibso.chicory.runtime.ImportValues;
import sculk.shaded.com.dylibso.chicory.runtime.Instance;
import sculk.shaded.com.dylibso.chicory.runtime.Memory;
import sculk.shaded.com.dylibso.chicory.wasi.WasiOptions;
import sculk.shaded.com.dylibso.chicory.wasi.WasiPreview1;
import sculk.shaded.com.dylibso.chicory.wasm.Parser;
import sculk.shaded.com.dylibso.chicory.wasm.WasmModule;

/**
 * One instance of rust/sculk-webrtc, compiled to JVM classes at build time
 * (libs/webrtc), with typed calls over its exports. See the crate's lib.rs
 * for the interface. Not thread-safe: {@link WebRtc} uses it from one thread.
 *
 * <p>A trap ends the instance: the crate aborts on a panic, with its
 * connection table still borrowed, so every later call would trap too. The
 * first one marks the instance {@link #broken}, and from then on each call
 * throws {@link Trap} without entering the module; the owner makes another.
 */
final class WebRtcModule {
    static final String WASM = "/sculk/remote/sculk_webrtc.wasm";
    static final int ADDR = 18;

    /** What {@link #poll} returns. */
    static final int WAIT = 0, TRANSMIT = 1, CHANNEL_OPEN = 2, CHANNEL_DATA = 3, CHANNEL_CLOSE = 4,
            BUFFERED_LOW = 5, CONNECTED = 6, ICE_STATE = 7, CLOSED = 8;

    private static WasmModule parsed;

    private final Instance instance;
    private final Memory memory;
    private final ExportFunction input, outputPtr, outputLen;
    private final ExportFunction rtcNew, rtcFree, addHost, addSrflx, acceptOffer, offer, acceptAnswer;
    private final ExportFunction accepts, receive, timeout, poll, write, buffered, setBufferedLow;
    private boolean broken;

    /** A call into the module failed; the message is the module's. */
    static final class ModuleError extends Exception {
        ModuleError(String message) {
            super(message);
        }
    }

    /** The module trapped, in this call or an earlier one: this instance is of no more use. */
    static final class Trap extends RuntimeException {
        Trap(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static synchronized WasmModule module() {
        if (parsed == null) {
            try (InputStream in = WebRtcModule.class.getResourceAsStream(WASM)) {
                if (in == null) throw new IllegalStateException(WASM + " missing from the mod");
                parsed = Parser.parse(in);
            } catch (IOException e) {
                throw new IllegalStateException(WASM, e);
            }
        }
        return parsed;
    }

    WebRtcModule() {
        var wasi = WasiPreview1.builder()
                .withOptions(WasiOptions.builder()
                        .withRandom(new SecureRandom())
                        .withStdout(OutputStream.nullOutputStream())
                        .withStderr(OutputStream.nullOutputStream())
                        .build())
                .build();
        var imports = ImportValues.builder()
                .addFunction(wasi.toHostFunctions())
                .addFunction(new HostAesGcm().functions())
                .build();
        instance = Instance.builder(module())
                .withImportValues(imports)
                .withMachineFactory(WebRtcMachine::new)
                .withStart(false)
                .build();
        instance.export("_initialize").apply(); // a WASI reactor
        memory = instance.memory();
        input = instance.export("input");
        outputPtr = instance.export("output_ptr");
        outputLen = instance.export("output_len");
        rtcNew = instance.export("rtc_new");
        rtcFree = instance.export("rtc_free");
        addHost = instance.export("rtc_add_host");
        addSrflx = instance.export("rtc_add_srflx");
        acceptOffer = instance.export("rtc_accept_offer");
        offer = instance.export("rtc_offer");
        acceptAnswer = instance.export("rtc_accept_answer");
        accepts = instance.export("rtc_accepts");
        receive = instance.export("rtc_receive");
        timeout = instance.export("rtc_timeout");
        poll = instance.export("rtc_poll");
        write = instance.export("rtc_write");
        buffered = instance.export("rtc_buffered");
        setBufferedLow = instance.export("rtc_set_buffered_low");
    }

    /** Whether a call has trapped. Every call throws {@link Trap} from then on. */
    boolean broken() {
        return broken;
    }

    /** Every entry to the module goes through here, so no trap goes unnoticed. */
    private long call(ExportFunction f, long... args) {
        if (broken) throw new Trap("the WebRTC module has stopped", null);
        try {
            long[] r = f.apply(args);
            return r == null || r.length == 0 ? 0 : r[0];
        } catch (RuntimeException | StackOverflowError e) {
            throw trap(e);
        }
    }

    private Trap trap(Throwable e) {
        broken = true;
        return new Trap("the WebRTC module trapped: " + e, e);
    }

    /** Traps, as a panic in the module does: for tests. An input buffer of 4 GiB is more than it can have. */
    void trap() {
        call(input, -1); // as a u32
    }

    private void put(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        int ptr = (int) call(input, len);
        try {
            for (byte[] p : parts) {
                memory.write(ptr, p);
                ptr += p.length;
            }
        } catch (RuntimeException e) {
            throw trap(e); // a pointer outside its memory: the module is not to be trusted further
        }
    }

    /** The output buffer, as left by the last call. */
    byte[] output() {
        int ptr = (int) call(outputPtr), len = (int) call(outputLen);
        try {
            return memory.readBytes(ptr, len);
        } catch (RuntimeException e) {
            throw trap(e);
        }
    }

    private int check(long r) throws ModuleError {
        if (r < 0) throw new ModuleError(new String(output(), StandardCharsets.UTF_8));
        return (int) r;
    }

    /** A new connection; {@code hostAes} seals DTLS records with the JDK's AES-GCM. */
    int create(boolean hostAes) {
        return (int) call(rtcNew, hostAes ? 1 : 0);
    }

    void free(int h) {
        call(rtcFree, h);
    }

    void addHost(int h, InetSocketAddress a) throws ModuleError {
        put(addr(a));
        check(call(addHost, h));
    }

    void addSrflx(int h, InetSocketAddress publicAddr, InetSocketAddress base) throws ModuleError {
        put(addr(publicAddr), addr(base));
        check(call(addSrflx, h));
    }

    /** The answer to a browser's offer. */
    String acceptOffer(int h, String sdp) throws ModuleError {
        put(sdp.getBytes(StandardCharsets.UTF_8));
        check(call(acceptOffer, h));
        return new String(output(), StandardCharsets.UTF_8);
    }

    /** An offer with one data channel: the browser's side, for tests. */
    String offer(int h, String label) throws ModuleError {
        put(label.getBytes(StandardCharsets.UTF_8));
        check(call(offer, h));
        return new String(output(), StandardCharsets.UTF_8);
    }

    void acceptAnswer(int h, String sdp) throws ModuleError {
        put(sdp.getBytes(StandardCharsets.UTF_8));
        check(call(acceptAnswer, h));
    }

    boolean accepts(int h, byte[] from, byte[] to, byte[] datagram) {
        put(from, to, datagram);
        return call(accepts, h) > 0;
    }

    void receive(int h, byte[] from, byte[] to, byte[] datagram) throws ModuleError {
        put(from, to, datagram);
        check(call(receive, h));
    }

    void timeout(int h) throws ModuleError {
        check(call(timeout, h));
    }

    /** The next output's code; its details are in {@link #output()}. */
    int poll(int h) throws ModuleError {
        return check(call(poll, h));
    }

    /** Whether the channel took the data (false: its buffer is full). */
    boolean write(int h, int channel, byte[] data, boolean binary) throws ModuleError {
        put(data);
        return check(call(write, h, channel, binary ? 1 : 0)) == 1;
    }

    int buffered(int h, int channel) throws ModuleError {
        return check(call(buffered, h, channel));
    }

    void setBufferedLow(int h, int channel, int threshold) throws ModuleError {
        check(call(setBufferedLow, h, channel, threshold));
    }

    /** An address as the module takes it: IPv6 (IPv4 mapped), then the port, big-endian. */
    static byte[] addr(InetSocketAddress a) {
        byte[] out = new byte[ADDR];
        byte[] ip = a.getAddress().getAddress();
        if (ip.length == 4) {
            out[10] = (byte) 0xff;
            out[11] = (byte) 0xff;
            System.arraycopy(ip, 0, out, 12, 4);
        } else {
            System.arraycopy(ip, 0, out, 0, 16);
        }
        out[16] = (byte) (a.getPort() >> 8);
        out[17] = (byte) a.getPort();
        return out;
    }

    static InetSocketAddress addr(byte[] b, int at) {
        byte[] ip = java.util.Arrays.copyOfRange(b, at, at + 16);
        boolean mapped = true;
        for (int i = 0; i < 10; i++) mapped &= ip[i] == 0;
        mapped &= ip[10] == (byte) 0xff && ip[11] == (byte) 0xff;
        int port = ((b[at + 16] & 0xff) << 8) | (b[at + 17] & 0xff);
        try {
            InetAddress a = mapped
                    ? Inet4Address.getByAddress(java.util.Arrays.copyOfRange(ip, 12, 16))
                    : InetAddress.getByAddress(ip);
            return new InetSocketAddress(a, port);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
