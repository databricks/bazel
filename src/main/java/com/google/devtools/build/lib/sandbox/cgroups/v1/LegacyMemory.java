package com.google.devtools.build.lib.sandbox.cgroups.v1;

import com.google.devtools.build.lib.sandbox.cgroups.Controller;
import com.google.devtools.build.lib.sandbox.cgroups.Monitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LegacyMemory implements Controller.Memory {
    private final Path path;
    private final Monitor monitor;

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public Path statFile() throws IOException {
        return path.resolve("memory.stat");
    }

    public LegacyMemory(Path path, Monitor.MonitorFactory factory) {
        this.path = path;
        this.monitor = factory.create(this);
    }

    @Override
    public void setMaxBytes(long bytes) throws IOException {
        Files.writeString(path.resolve("memory.limit_in_bytes"), Long.toString(bytes));
    }

    @Override
    public long getMaxBytes() throws IOException {
        return Long.parseLong(Files.readString(path.resolve("memory.limit_in_bytes")).trim());
    }

    @Override
    public long oomKills() throws IOException {
        for (String line: Files.readAllLines(getPath().resolve("memory.oom_control"))) {
            if (line.startsWith("oom_kill ")) {
                return Long.parseLong(line.substring(line.indexOf(" ") + 1));
            }
        }
        return -1;
    }

    @Override
    public long maxUsage() throws IOException {
        return Long.parseLong(Files.readString(path.resolve("memory.max_usage_in_bytes")).trim());
    }

    @Override
    public Monitor monitor() {
        return monitor;
    }
}
