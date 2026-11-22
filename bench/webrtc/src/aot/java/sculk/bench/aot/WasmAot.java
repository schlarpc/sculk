package sculk.bench.aot;

import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.internal.ByteClassCollector;
import com.dylibso.chicory.compiler.internal.Compiler;
import com.dylibso.chicory.wasm.Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Compiles a wasm module to JVM classes with Chicory's compiler, at build time.
 *
 * <p>Args: the wasm file, the machine's class name, the output directory, and
 * a package prefix to relocate with what it becomes (internal names), since
 * the classes run against the relocated runtime in libs/chicory. The machine
 * is {@code new <name>(Instance)}; functions the compiler cannot handle (too
 * large for one JVM method) fall back to the interpreter, and are listed.
 */
public final class WasmAot {
    public static void main(String[] args) throws Exception {
        Path wasm = Path.of(args[0]);
        String className = args[1];
        Path out = Path.of(args[2]);
        String from = args[3];
        String to = args[4];

        var module = Parser.parse(wasm);
        var result = Compiler.builder(module)
                .withClassName(className)
                .withClassCollectorFactory(ByteClassCollector::new)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .build()
                .compile();

        Remapper relocate = new Remapper(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public String map(String name) {
                return name.startsWith(from) ? to + name.substring(from.length()) : name;
            }
        };
        long bytes = 0;
        for (Map.Entry<String, byte[]> e : result.classBytes().entrySet()) {
            var writer = new ClassWriter(0);
            new ClassReader(e.getValue()).accept(new ClassRemapper(writer, relocate), 0);
            byte[] b = writer.toByteArray();
            Path file = out.resolve(e.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, b);
            bytes += b.length;
        }
        int funcs = module.functionSection().functionCount();
        System.out.printf("%s: %d functions, %d classes, %d KiB; interpreted: %s%n",
                wasm.getFileName(), funcs, result.classBytes().size(), bytes / 1024,
                result.interpretedFunctions().isEmpty() ? "none" : result.interpretedFunctions());
    }
}
