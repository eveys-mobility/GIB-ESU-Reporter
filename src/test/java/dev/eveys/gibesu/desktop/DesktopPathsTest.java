package dev.eveys.gibesu.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopPathsTest {
    @TempDir
    Path selectedDirectory;

    @Test
    void defaultOutputIsAbsoluteAndInsideUserHome() {
        Path out = DesktopPaths.defaultOutputDirectory();
        assertTrue(out.isAbsolute());
        assertTrue(out.startsWith(Path.of(System.getProperty("user.home")).toAbsolutePath()));
        assertEquals("out-desktop", out.getFileName().toString());
    }

    @Test
    void relativeOutputUsesUserDirectoryInsteadOfWorkingDirectory() {
        assertEquals(DesktopPaths.defaultOutputDirectory().resolve("2026-08"),
                DesktopPaths.resolveOutputDirectory("out-desktop/2026-08"));
    }

    @Test
    void explicitAbsoluteDirectoryIsPreserved() {
        assertEquals(selectedDirectory.toAbsolutePath(),
                DesktopPaths.resolveOutputDirectory(selectedDirectory.toString()));
    }
}
