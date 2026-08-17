package com.hitit.aviation.core.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ProjectRoot {
    private ProjectRoot() {}
    public static Path find() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path root = cwd;
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("pom.xml"))) root = dir;
            else if (!dir.equals(cwd)) break;
        }
        return root;
    }
}
