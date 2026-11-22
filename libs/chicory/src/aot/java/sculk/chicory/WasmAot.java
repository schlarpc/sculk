package sculk.chicory;

import static com.dylibso.chicory.wasm.Encoding.readVarUInt32;
import static com.dylibso.chicory.wasm.WasmWriter.writeVarUInt32;

import com.dylibso.chicory.compiler.InterpreterFallback;
import com.dylibso.chicory.compiler.internal.ByteClassCollector;
import com.dylibso.chicory.compiler.internal.Compiler;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmWriter;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.RawSection;
import com.dylibso.chicory.wasm.types.SectionId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Compiles a wasm module to JVM classes with Chicory's compiler, at build
 * time, so the game neither ships ASM nor generates classes.
 *
 * <p>Args: the wasm file; the machine's class name; the directory for the
 * classes; a package prefix to relocate, and what it becomes (internal
 * names), since the classes run against libs/chicory's relocated runtime;
 * and optionally where to write the module the game loads beside them.
 *
 * <p>The machine is {@code new <name>(Instance)}. Functions the compiler
 * cannot handle (too large for one JVM method) fall back to the
 * interpreter, and are listed. The module written for the game keeps only
 * those functions' bodies, and no custom sections (debug names): Chicory
 * still parses it for types, memory, tables and data. That trimming follows
 * Chicory's own build-time Generator.generateMetaWasm (Apache-2.0).
 */
public final class WasmAot {
    public static void main(String[] args) throws Exception {
        Path wasm = Path.of(args[0]);
        String className = args[1];
        Path out = Path.of(args[2]);
        String from = args[3];
        String to = args[4];
        Path moduleOut = args.length > 5 ? Path.of(args[5]) : null;

        byte[] wasmBytes = Files.readAllBytes(wasm);
        var module = Parser.parse(wasmBytes);
        var result = Compiler.builder(module)
                .withClassName(className)
                .withClassCollectorFactory(ByteClassCollector::new)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .build()
                .compile();

        Remapper relocate = new Remapper(Opcodes.ASM9) {
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
        String trimmed = "";
        if (moduleOut != null) {
            byte[] meta = trim(wasmBytes, result.interpretedFunctions());
            Files.createDirectories(moduleOut.getParent());
            Files.write(moduleOut, meta);
            trimmed = String.format("; module for the game %d KiB (from %d)", meta.length / 1024, wasmBytes.length / 1024);
        }
        System.out.printf("%s: %d functions, %d classes, %d KiB; interpreted: %s%s%n",
                wasm.getFileName(), funcs, result.classBytes().size(), bytes / 1024,
                result.interpretedFunctions().isEmpty() ? "none" : result.interpretedFunctions(), trimmed);
    }

    /** The module with compiled functions' bodies replaced by `unreachable`, and no custom sections. */
    static byte[] trim(byte[] wasmBytes, Set<Integer> interpreted) {
        var module = Parser.builder().includeSectionId(SectionId.CODE).build().parse(wasmBytes);
        int imports = module.importSection().importCount();
        var writer = new WasmWriter();
        Parser.parseWithoutDecoding(wasmBytes, section -> {
            if (section.sectionId() == SectionId.CODE) {
                var source = ByteBuffer.wrap(((RawSection) section).contents());
                var code = new ByteArrayOutputStream();
                int count = (int) readVarUInt32(source);
                writeVarUInt32(code, count);
                for (int i = 0; i < count; i++) {
                    int size = (int) readVarUInt32(source);
                    if (interpreted.contains(imports + i)) {
                        writeVarUInt32(code, size);
                        byte[] body = new byte[size];
                        source.get(body);
                        code.writeBytes(body);
                    } else {
                        source.position(source.position() + size);
                        writeVarUInt32(code, 3); // body size
                        writeVarUInt32(code, 0); // no locals
                        code.write(OpCode.UNREACHABLE.opcode());
                        code.write(OpCode.END.opcode());
                    }
                }
                writer.writeSection(SectionId.CODE, code.toByteArray());
            } else if (section.sectionId() != SectionId.CUSTOM) {
                writer.writeSection((RawSection) section);
            }
        });
        return writer.bytes();
    }
}
