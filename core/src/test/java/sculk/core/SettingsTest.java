package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {
    @TempDir
    Path dir;

    @Test
    void aMissingFileIsWrittenWithEveryDefaultAndReadsBackAsThem() throws Exception {
        Path file = dir.resolve("config").resolve("sculk.toml");
        Settings.Source first = Settings.tomlFile(file);
        assertNull(first.get("captureBytes"));
        String text = Files.readString(file);
        for (Settings.Setting s : Settings.ALL) assertTrue(text.contains("\n" + s.key() + " = "), s.key());
        Settings.Source again = Settings.tomlFile(file);
        for (Settings.Setting s : Settings.ALL) assertEquals(String.valueOf(s.defaultValue()), String.valueOf(again.get(s.key())), s.key());
    }

    @Test
    void valuesAreReadFromTheFile() throws Exception {
        Path file = dir.resolve("sculk.toml");
        Files.writeString(file, """
                # comment
                control = "off"   # trailing
                captureBytes = 1_048_576
                watch = false
                captureDir = 'C:\\temp'
                controlToken = "a \\"quoted\\" one"
                """);
        Settings.Source s = Settings.tomlFile(file);
        assertEquals("off", s.get("control"));
        assertEquals("1048576", s.get("captureBytes"));
        assertEquals("false", s.get("watch"));
        assertEquals("C:\\temp", s.get("captureDir"));
        assertEquals("a \"quoted\" one", s.get("controlToken"));
    }

    @Test
    void aSystemPropertyOverridesTheConfigWhichOverridesTheDefault() {
        Settings.use(key -> key.equals("controlEvents") ? 7 : null);
        try {
            assertEquals(7, Settings.intValue("controlEvents"));
            assertEquals(128L << 20, Settings.longValue("controlFrameBytes"));
            System.setProperty("sculk.controlEvents", "9");
            assertEquals(9, Settings.intValue("controlEvents"));
        } finally {
            System.clearProperty("sculk.controlEvents");
            Settings.use(null);
        }
    }

    @Test
    void aBadNumberFallsBackToTheDefault() {
        Settings.use(key -> key.equals("captureBytes") ? "lots" : null);
        try {
            assertEquals(2L << 30, Settings.longValue("captureBytes"));
        } finally {
            Settings.use(null);
        }
    }
}
