package com.google.devtools.build.lib.sandbox.cgroups.v2;

import com.google.devtools.build.lib.sandbox.cgroups.Controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

public class UnifiedMemory implements Controller.Memory {
    private final Path path;
    public UnifiedMemory(Path path) {
        this.path = path;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public Path statFile() throws IOException {
        return path.resolve("memory.stat");
    }

    @Override
    public void setMaxBytes(long bytes) throws IOException {
        Files.writeString(path.resolve("memory.max"), Long.toString(bytes));
    }

    @Override
    public long getMaxBytes() throws IOException {
        return Long.parseLong(Files.readString(path.resolve("memory.max")).trim());
    }

    @Override
    public long oomKills() throws IOException {
        for (String line: Files.readAllLines(getPath().resolve("memory.events"))) {
            if (line.startsWith("oom_kill ")) {
                return Long.parseLong(line.substring(line.indexOf(" ") + 1));
            }
        }
        return -1;
    }

    @Override
    public long maxUsage() throws IOException {
        // This file has been added relatively recently, so it might not exist.
        // Return -1 in that case, to signal its absence
        // Ref. https://github.com/torvalds/linux/commit/8e20d4b332660a32e842e20c34cfc3b3456bc6dc
        if (path.resolve("memory.peak").toFile().exists()) {
            return Long.parseLong(Files.readString(path.resolve("memory.peak")).trim());
        }
        return -1;
    }
}
