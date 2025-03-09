package com.google.devtools.build.lib.sandbox.cgroups.v1;

import com.google.devtools.build.lib.sandbox.cgroups.Controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LegacyNetCls implements Controller.NetCls {
    private final Path path;

    @Override
    public Path getPath() throws IOException {
        return path;
    }

    public LegacyNetCls(Path path) {
        this.path = path;
    }

    @Override
    public Path statFile() throws IOException {
        return path.resolve("net_cls.stat");
    }

    @Override
    public void setNetCls(int netCls) throws IOException {
        Files.writeString(path.resolve("net_cls.classid"), Integer.toString(netCls));
    }

    @Override
    public int getNetCls() throws IOException {
        return Integer.parseInt(Files.readString(path.resolve("net_cls.classid")).trim());
    }
}