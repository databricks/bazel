package com.google.devtools.build.lib.sandbox.cgroups.v1;

import com.google.devtools.build.lib.sandbox.cgroups.Controller;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LegacyCpuAcct implements Controller.CpuAcct {
    private final Path path;
    // TODO get this value from the system
    public static long USER_HZ = 100;

    public LegacyCpuAcct(Path path) {
        this.path = path;
    }

    @Override
    public Path getPath() throws IOException {
        return path;
    }

    @Override
    public Path statFile() throws IOException {
        return path.resolve("cpuacct.stat");
    }

    public long getUsage() throws IOException {
        return Long.parseLong(Files.readString(path.resolve("cpuacct.usage")).trim());
    }
}
