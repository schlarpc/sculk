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

    /** A call into the module failed; the message is the module's. */
    static final class ModuleError extends Exception {
        ModuleError(String message) {
            super(message);
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

    private void put(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        int ptr = (int) input.apply(len)[0];
        for (byte[] p : parts) {
            memory.write(ptr, p);
            ptr += p.length;
        }
    }

    /** The output buffer, as left by the last call. */
    byte[] output() {
        return memory.readBytes((int) outputPtr.apply()[0], (int) outputLen.apply()[0]);
    }

    private int check(long r) throws ModuleError {
        if (r < 0) throw new ModuleError(new String(output(), StandardCharsets.UTF_8));
        return (int) r;
    }

    /** A new connection; {@code hostAes} seals DTLS records with the JDK's AES-GCM. */
    int create(boolean hostAes) {
        return (int) rtcNew.apply(hostAes ? 1 : 0)[0];
    }

    void free(int h) {
        rtcFree.apply(h);
    }

    void addHost(int h, InetSocketAddress a) throws ModuleError {
        put(addr(a));
        check(addHost.apply(h)[0]);
    }

    void addSrflx(int h, InetSocketAddress publicAddr, InetSocketAddress base) throws ModuleError {
        put(addr(publicAddr), addr(base));
        check(addSrflx.apply(h)[0]);
    }

    /** The answer to a browser's offer. */
    String acceptOffer(int h, String sdp) throws ModuleError {
        put(sdp.getBytes(StandardCharsets.UTF_8));
        check(acceptOffer.apply(h)[0]);
        return new String(output(), StandardCharsets.UTF_8);
    }

    /** An offer with one data channel: the browser's side, for tests. */
    String offer(int h, String label) throws ModuleError {
        put(label.getBytes(StandardCharsets.UTF_8));
        check(offer.apply(h)[0]);
        return new String(output(), StandardCharsets.UTF_8);
    }

    void acceptAnswer(int h, String sdp) throws ModuleError {
        put(sdp.getBytes(StandardCharsets.UTF_8));
        check(acceptAnswer.apply(h)[0]);
    }

    boolean accepts(int h, byte[] from, byte[] to, byte[] datagram) {
        put(from, to, datagram);
        return accepts.apply(h)[0] > 0;
    }

    void receive(int h, byte[] from, byte[] to, byte[] datagram) throws ModuleError {
        put(from, to, datagram);
        check(receive.apply(h)[0]);
    }

    void timeout(int h) throws ModuleError {
        check(timeout.apply(h)[0]);
    }

    /** The next output's code; its details are in {@link #output()}. */
    int poll(int h) throws ModuleError {
        return check(poll.apply(h)[0]);
    }

    /** Whether the channel took the data (false: its buffer is full). */
    boolean write(int h, int channel, byte[] data, boolean binary) throws ModuleError {
        put(data);
        return check(write.apply(h, channel, binary ? 1 : 0)[0]) == 1;
    }

    int buffered(int h, int channel) throws ModuleError {
        return check(buffered.apply(h, channel)[0]);
    }

    void setBufferedLow(int h, int channel, int threshold) throws ModuleError {
        check(setBufferedLow.apply(h, channel, threshold)[0]);
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
