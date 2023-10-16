package com.google.devtools.build.lib.sandbox.cgroups.v2;

import com.google.devtools.build.lib.sandbox.cgroups.Controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class UnifiedCpu implements Controller.Cpu {
    private final Path path;
    public UnifiedCpu(Path path) {
        this.path = path;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public void setCpus(float cpus) throws IOException {
        int period = 1000_000;
        int quota = Math.round(period * cpus);
        String limit = String.format("%d %d", quota, period);
        Files.writeString(path.resolve("cpu.max"), limit);
    }

    @Override
    public int getCpus() throws IOException {
        return Integer.parseInt(Files.readString(path.resolve("cpu.max")).trim());
    }
}
