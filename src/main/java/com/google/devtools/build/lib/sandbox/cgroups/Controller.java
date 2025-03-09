package com.google.devtools.build.lib.sandbox.cgroups;

import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.server.FailureDetails;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

public interface Controller {
    default boolean isLegacy() throws IOException {
        return !getPath().resolve("cgroup.controllers").toFile().exists();
    }

    static <T extends Controller> T getDefault(Class<T> clazz) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws ExecException {
                throw new ExecException("Cgroup requested by cgroups are not available!") {
                    protected FailureDetails.FailureDetail getFailureDetail(String message) {
                        return FailureDetails.FailureDetail.newBuilder().setMessage(message).build();
                    }
                };

            }
        };

        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[]{clazz}, handler);
    }

    Path getPath() throws IOException;

    Path statFile() throws IOException;

    default String getStats() throws IOException {
        if (statFile() != null && statFile().toFile().exists()) {
            return Files.readString(statFile());
        }
        return "";
    }

    interface Memory extends Controller, Monitor.Monitorable {
        void setMaxBytes(long bytes) throws IOException;
        long getMaxBytes() throws IOException;
        long oomKills() throws IOException;
        long maxUsage() throws IOException;
    }
    interface Cpu extends Controller {
        void setCpus(float cpus) throws IOException;
        int getCpus() throws IOException;
        int getPeriod() throws IOException;
    }
    interface CpuAcct extends Controller {
        long getUsage() throws IOException;
    }
    interface NetCls extends Controller {
        void setNetCls(int netCls) throws IOException;
        int getNetCls() throws IOException;
    }
}
