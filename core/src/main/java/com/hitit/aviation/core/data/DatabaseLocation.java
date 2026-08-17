package com.hitit.aviation.core.data;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class DatabaseLocation {

    private static final String DB_FILE_NAME = "route-optimizer.db";
    private static final String PROPERTY_KEY = "routeoptimizer.db.path";

    private DatabaseLocation() {}

    public static Path resolve() {
        String override = System.getProperty(PROPERTY_KEY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        return ProjectRoot.find().resolve("database").resolve(DB_FILE_NAME);
    }

}