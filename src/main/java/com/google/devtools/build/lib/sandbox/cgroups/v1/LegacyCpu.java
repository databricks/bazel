package com.google.devtools.build.lib.sandbox.cgroups.v1;

import com.google.devtools.build.lib.sandbox.cgroups.Controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LegacyCpu implements Controller.Cpu {
    private final Path path;
    private final int period;

    public LegacyCpu(Path path) throws IOException {
        this.path = path;
        this.period = Integer.parseInt(Files.readString(path.resolve("cpu.cfs_period_us")).trim());
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public void setCpus(float cpus) throws IOException {
        int quota = Math.round(cpus * period);
        Files.writeString(path.resolve("cpu.cfs_quota_us"), Integer.toString(quota));
    }

    @Override
    public int getCpus() throws IOException {
        return Integer.parseInt(Files.readString(path.resolve("cpu.cfs_quota_us")).trim());
    }
}
