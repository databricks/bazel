package com.google.devtools.build.lib.sandbox.cgroups.v2;

import com.google.devtools.build.lib.sandbox.cgroups.Controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class UnifiedCpu implements Controller.Cpu {
    private final Path path;
    private final int period;
    public UnifiedCpu(Path path) throws IOException {
        this.path = path;
        this.period = Integer.parseInt(Files.readString(path.resolve("cpu.max")).split(" ", 2)[1]);
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public Path statFile() throws IOException {
        return path.resolve("cpu.stat");
    }

    @Override
    public void setCpus(float cpus) throws IOException {
        int quota = Math.round(period * cpus);
        String limit = String.format("%d %d", quota, period);
        Files.writeString(path.resolve("cpu.max"), limit);
    }

    @Override
    public int getCpus() throws IOException {
        return Integer.parseInt(Files.readString(path.resolve("cpu.max")).trim());
    }

    public int getPeriod() {
        return period;
    }
}
