package sculk.core.remote;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import sculk.shaded.com.dylibso.chicory.runtime.HostFunction;
import sculk.shaded.com.dylibso.chicory.runtime.Instance;
import sculk.shaded.com.dylibso.chicory.runtime.Memory;
import sculk.shaded.com.dylibso.chicory.wasm.types.FunctionType;
import sculk.shaded.com.dylibso.chicory.wasm.types.ValType;

/**
 * The {@code sculk} host module that rust/sculk-webrtc imports: AES-GCM from
 * the JDK (AES-NI and CLMUL in HotSpot) for DTLS records, which in wasm would
 * be software (bench/webrtc/README.md), and this thread's CPU time. See
 * rust/sculk-webrtc-crypto/src/host_aes.rs for the contract. One per
 * instance, used from one thread.
 */
final class HostAesGcm {
    private static final int TAG_BITS = 128;
    private static final int TAG = TAG_BITS / 8;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static final class Gcm {
        final SecretKeySpec key;
        final Cipher cipher;
        byte[] out = new byte[2048];

        Gcm(byte[] key) throws GeneralSecurityException {
            this.key = new SecretKeySpec(key, "AES");
            this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        byte[] out(int len) {
            if (out.length < len) out = new byte[Math.max(len, out.length * 2)];
            return out;
        }
    }

    private final List<Gcm> handles = new ArrayList<>();

    HostFunction[] functions() {
        var i32 = ValType.I32;
        return new HostFunction[] {
            new HostFunction("sculk", "thread_cpu_ns",
                    FunctionType.of(List.of(), List.of(ValType.I64)),
                    (inst, args) -> new long[] {THREADS.getCurrentThreadCpuTime()}),
            new HostFunction("sculk", "aes_gcm_new",
                    FunctionType.of(List.of(i32, i32), List.of(i32)),
                    (inst, args) -> new long[] {gcmNew(inst.memory(), (int) args[0], (int) args[1])}),
            new HostFunction("sculk", "aes_gcm_free",
                    FunctionType.of(List.of(i32), List.of()),
                    (inst, args) -> {
                        handles.set((int) args[0], null);
                        return null;
                    }),
            new HostFunction("sculk", "aes_gcm_seal",
                    FunctionType.of(List.of(i32, i32, i32, i32, i32, i32), List.of(i32)),
                    (inst, args) -> new long[] {seal(inst, args)}),
            new HostFunction("sculk", "aes_gcm_open",
                    FunctionType.of(List.of(i32, i32, i32, i32, i32, i32), List.of(i32)),
                    (inst, args) -> new long[] {open(inst, args)}),
        };
    }

    private int gcmNew(Memory memory, int keyPtr, int keyLen) {
        if (keyLen != 16 && keyLen != 32) return -1;
        try {
            var gcm = new Gcm(memory.readBytes(keyPtr, keyLen));
            int free = handles.indexOf(null);
            if (free >= 0) {
                handles.set(free, gcm);
                return free;
            }
            handles.add(gcm);
            return handles.size() - 1;
        } catch (GeneralSecurityException e) {
            return -1;
        }
    }

    /** handle, nonce, aad, aad_len, buf, len: encrypts buf[..len], tag after it. */
    private int seal(Instance inst, long[] a) {
        Gcm gcm = handles.get((int) a[0]);
        Memory m = inst.memory();
        int buf = (int) a[4];
        int len = (int) a[5];
        try {
            gcm.cipher.init(Cipher.ENCRYPT_MODE, gcm.key,
                    new GCMParameterSpec(TAG_BITS, m.readBytes((int) a[1], 12)));
            gcm.cipher.updateAAD(m.readBytes((int) a[2], (int) a[3]));
            byte[] out = gcm.out(len + TAG);
            int n = gcm.cipher.doFinal(m.readBytes(buf, len), 0, len, out, 0);
            m.write(buf, out, 0, n);
            return 0;
        } catch (GeneralSecurityException e) {
            return 1;
        }
    }

    /** handle, nonce, aad, aad_len, buf, len: checks the tag, decrypts buf[..len-16]. */
    private int open(Instance inst, long[] a) {
        Gcm gcm = handles.get((int) a[0]);
        Memory m = inst.memory();
        int buf = (int) a[4];
        int len = (int) a[5];
        try {
            gcm.cipher.init(Cipher.DECRYPT_MODE, gcm.key,
                    new GCMParameterSpec(TAG_BITS, m.readBytes((int) a[1], 12)));
            gcm.cipher.updateAAD(m.readBytes((int) a[2], (int) a[3]));
            byte[] out = gcm.out(len);
            int n = gcm.cipher.doFinal(m.readBytes(buf, len), 0, len, out, 0);
            m.write(buf, out, 0, n);
            return 0;
        } catch (AEADBadTagException e) {
            return 2;
        } catch (GeneralSecurityException e) {
            return 1;
        }
    }
}
