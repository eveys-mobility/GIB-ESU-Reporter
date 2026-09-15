package dev.eveys.gibesu.desktop;

import java.nio.file.Path;

final class DesktopPaths {
    private DesktopPaths() {}

    static Path defaultOutputDirectory() {
        return resolveOutputDirectory("out-desktop");
    }

    static Path resolveOutputDirectory(String value) {
        Path path = Path.of(value == null || value.isBlank() ? "out-desktop" : value.trim());
        // Finder may launch the app with / as its working directory.
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.home"), "EveysGibEsuReporter").resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
}
