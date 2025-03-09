package com.google.devtools.build.lib.sandbox.cgroups;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.common.io.CharSink;
import com.google.common.io.Files;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.sandbox.cgroups.v1.LegacyCpu;
import com.google.devtools.build.lib.sandbox.cgroups.v1.LegacyCpuAcct;
import com.google.devtools.build.lib.sandbox.cgroups.v1.LegacyMemory;
import com.google.devtools.build.lib.sandbox.cgroups.v1.LegacyNetCls;
import com.google.devtools.build.lib.sandbox.cgroups.v2.UnifiedCpu;
import com.google.devtools.build.lib.sandbox.cgroups.v2.UnifiedMemory;
import com.google.devtools.build.lib.sandbox.cgroups.v2.UnifiedNetCls;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * This class creates and exposes a virtual cgroup for the bazel process and allows creating
 * child cgroups. Resources are exposed as {@link Controller}s, each representing a
 * subsystem within the virtual cgroup and that could in theory belong to different real cgroups.
 */
@AutoValue
public abstract class VirtualCGroup {
    private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
    private static final File PROC_SELF_MOUNTS_PATH = new File("/proc/self/mounts");
    private static final File PROC_SELF_CGROUP_PATH = new File("/proc/self/cgroup");

    private static final Monitor.MonitorFactory factory = new Monitor.MonitorFactory();

    @Nullable
    private static volatile VirtualCGroup instance;

    public abstract Controller.Cpu cpu();
    public abstract Controller.Memory memory();
    @Nullable
    public abstract Controller.CpuAcct cpuacct();
    @Nullable
    public abstract Controller.NetCls netCls();

    public abstract ImmutableSet<Path> paths();

    private final Queue<VirtualCGroup> children = new ConcurrentLinkedQueue<>();

    public static VirtualCGroup getInstance(EventHandler reporter) throws IOException {
        if (instance == null) {
            synchronized (VirtualCGroup.class) {
                if (instance == null) {
                    instance = create(reporter);
                }
            }
        }
        return instance;
    }

    public static void deleteInstance() {
        if (instance != null) {
            synchronized (VirtualCGroup.class) {
                if (instance != null) {
                    instance.delete();
                    instance = null;
                }
            }
        }
    }

    public static VirtualCGroup create(EventHandler reporter) throws IOException {
        return create(PROC_SELF_MOUNTS_PATH, PROC_SELF_CGROUP_PATH, reporter);
    }

    private static void copyControllersToSubtree(Path cgroup) throws IOException {
        File subtree = cgroup.resolve("cgroup.subtree_control").toFile();
        File controllers = cgroup.resolve("cgroup.controllers").toFile();
        if (subtree.canWrite() && controllers.canRead()) {
            CharSink sink = Files.asCharSink(subtree, StandardCharsets.UTF_8);
            Scanner scanner = new Scanner(controllers);
            while (scanner.hasNext()) {
                sink.write("+" + scanner.next());
            }
        }
    }

    static VirtualCGroup create(File procMounts, File procCgroup, EventHandler reporter) throws IOException {
        final List<Mount> mounts = Mount.parse(procMounts);
        final Map<String, Hierarchy> hierarchies = Hierarchy.parse(procCgroup)
            .stream()
            .flatMap(h -> h.controllers().stream().map(c -> Map.entry(c, h)))
            // For cgroup v2, there are no controllers specified in the proc/pid/cgroup file
            // So the keep will be empty and unique. For cgroup v1, there could potentially
            // be multiple mounting points for the same controller, but they represent a
            // "view of the same hierarchy" so it is ok to just keep one.
            // Ref. https://man7.org/linux/man-pages/man7/cgroups.7.html
            .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));

        Controller.Memory memory = null;
        Controller.Cpu cpu = null;
        Controller.CpuAcct cpuacct = null;
        Controller.NetCls netCls = null;
        ImmutableSet.Builder<Path> paths = ImmutableSet.builder();

        for (Mount m: mounts) {
            if (memory != null && cpu != null) break;

            if (m.isV2()) {
                Hierarchy h = hierarchies.get("");
                if (h == null) continue;
                Path cgroup = m.path().resolve(Paths.get("/").relativize(h.path()));
                if (!cgroup.equals(m.path())) {
                    // Because of the "no internal processes" rule, it is not possible to
                    // create a non-empty child cgroups on non-root cgroups with member processes
                    // Instead, we go up one level in the hierarchy and declare a sibling.
                    cgroup = cgroup.getParent();
                }
                if (!cgroup.toFile().canWrite()) {
                    reporter.handle(Event.warn("Found non-writable cgroup v2 at " + cgroup));
                    continue;
                }
                try (InputStream s = new FileInputStream(cgroup.resolve("cgroup.procs").toFile())) {
                    // Check if the cgroup is empty, i.e. there are no member processes
                    // before modifying the subtree control to respect the "no internal processes"
                    if (s.read() != -1) {
                        reporter.handle(Event.warn("Found non-empty cgroup v2 at " + cgroup));
                        continue;
                    }
                    copyControllersToSubtree(cgroup);
                }

                cgroup = cgroup.resolve("bazel_" + ProcessHandle.current().pid() + ".slice");
                cgroup.toFile().mkdirs();
                paths.add(cgroup);

                Scanner scanner = new Scanner(cgroup.resolve("cgroup.controllers").toFile());
                while (scanner.hasNext()) {
                    switch (scanner.next()) {
                        case "memory":
                            if (memory != null) continue;
                            logger.atInfo().log("Found cgroup v2 memory controller at %s", cgroup);
                            memory = new UnifiedMemory(cgroup, factory);
                            break;
                        case "cpu":
                            if (cpu != null) continue;
                            logger.atInfo().log("Found cgroup v2 cpu controller at %s", cgroup);
                            cpu = new UnifiedCpu(cgroup);
                            break;
                        case "net_cls":
                            if (netCls != null) continue;
                            logger.atInfo().log("Found cgroup v2 net_cls controller at %s", cgroup);
                            netCls = new UnifiedNetCls(cgroup);
                            break;
                    }
                }
            } else {
                for (String opt : m.opts()) {
                    Hierarchy h = hierarchies.get(opt);
                    if (h == null) continue;
                    Path cgroup = m.path().resolve(Paths.get("/").relativize(h.path()));
                    if (!cgroup.toFile().canWrite()) {
                        reporter.handle(Event.warn("Found non-writable cgroup v1 at " + cgroup));
                        continue;
                    }
                    cgroup = cgroup.resolve("bazel_" + ProcessHandle.current().pid() + ".slice");
                    cgroup.toFile().mkdirs();
                    paths.add(cgroup);

                    switch (opt) {
                        case "memory":
                            if (memory != null) continue;
                            logger.atInfo().log("Found cgroup v1 memory controller at %s", cgroup);
                            memory = new LegacyMemory(cgroup, factory);
                            break;
                        case "cpu":
                            if (cpu != null) continue;
                            logger.atInfo().log("Found cgroup v1 cpu controller at %s", cgroup);
                            cpu = new LegacyCpu(cgroup);
                            break;
                        case "cpuacct":
                            if (cpuacct != null) continue;
                            logger.atInfo().log("Found cgroup v1 cpuacct controller at %s", cgroup);
                            cpuacct = new LegacyCpuAcct(cgroup);
                            break;
                        case "net_cls":
                            if (netCls != null) continue;
                            logger.atInfo().log("Found cgroup v1 net_cls controller at %s", cgroup);
                            netCls = new LegacyNetCls(cgroup);
                            break;
                    }
                }
            }
        }

        cpu = cpu != null ? cpu : Controller.getDefault(Controller.Cpu.class);
        memory = memory != null ? memory : Controller.getDefault(Controller.Memory.class);
        netCls = netCls != null ? netCls : Controller.getDefault(Controller.NetCls.class);
        VirtualCGroup vcgroup = new AutoValue_VirtualCGroup(cpu, memory, cpuacct, netCls, paths.build());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> vcgroup.delete()));
        return vcgroup;
    }

    public void delete() {
        this.children.forEach(VirtualCGroup::delete);
        this.paths().stream().map(Path::toFile).filter(File::exists).forEach(File::delete);
    }

    public VirtualCGroup child(String name) throws IOException {
        Controller.Cpu cpu = Controller.getDefault(Controller.Cpu.class);
        Controller.Memory memory = Controller.getDefault(Controller.Memory.class);
        Controller.NetCls netCls = null;
        Controller.CpuAcct cpuacct = null;
        ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
        if (memory() != null && memory().getPath() != null) {
            copyControllersToSubtree(memory().getPath());
            Path cgroup = memory().getPath().resolve(name);
            cgroup.toFile().mkdirs();
            memory = memory().isLegacy() ?
                new LegacyMemory(cgroup, factory) :
                new UnifiedMemory(cgroup, factory);
            paths.add(cgroup);
        }
        if (cpu() != null && cpu().getPath() != null) {
            copyControllersToSubtree(cpu().getPath());
            Path cgroup = cpu().getPath().resolve(name);
            cgroup.toFile().mkdirs();
            cpu = cpu().isLegacy() ? new LegacyCpu(cgroup) : new UnifiedCpu(cgroup);
            paths.add(cgroup);
        }
        if (cpuacct() != null && cpuacct().getPath() != null) {
            Path cgroup = cpuacct().getPath().resolve(name);
            cgroup.toFile().mkdirs();
            cpuacct = new LegacyCpuAcct(cgroup);
            paths.add(cgroup);
        }
        if (netCls() != null && netCls().getPath() != null) {
            copyControllersToSubtree(netCls().getPath());
            Path cgroup = netCls().getPath().resolve(name);
            cgroup.toFile().mkdirs();
            netCls = new LegacyNetCls(cgroup);
            paths.add(cgroup);
        }
        VirtualCGroup child = new AutoValue_VirtualCGroup(cpu, memory, cpuacct, netCls, paths.build());
        this.children.add(child);
        return child;
    }

    public void logStats() throws IOException {
        long now = System.nanoTime();
        if (cpu() != null || cpuacct() != null) {
            StringBuilder stats = new StringBuilder();
            if (cpu() != null) {
                stats.append(cpu().getStats());
                stats.append("quota").append(" ").append(cpu().getCpus()).append("\n");
                stats.append("period").append(" ").append(cpu().getPeriod()).append("\n");
            }
            if (cpuacct() != null) {
                try (BufferedReader reader = new BufferedReader(new StringReader(cpuacct().getStats()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split(" ", 2);
                        Double value = Long.parseLong(parts[1]) * 1e6 / LegacyCpuAcct.USER_HZ;
                        stats.append(parts[0]).append("_usec ").append(value.longValue()).append("\n");
                    }
                }
                stats.append("usage_usec").append(" ").append(cpuacct().getUsage() / 1000).append("\n");
            }
            Profiler.instance().logEventAtTime(now, ProfilerTask.SANDBOX_CPU_INFO, stats.toString());
        }
        if (memory() != null) {
            Long kills = memory().oomKills();
            Long limit = memory().getMaxBytes();
            Long usage = memory().maxUsage();

            StringBuilder stats = new StringBuilder();
            if (usage > 0) stats.append("max_usage_in_bytes").append(" ").append(usage).append("\n");
            if (limit > 0) stats.append("limit_in_bytes").append(" ").append(limit).append("\n");
            if (kills > 0) stats.append("oom_kills").append(" ").append(kills).append("\n");

            for (Map.Entry<String, Long> stat : memory().monitor().stop().entrySet()) {
                stats.append(stat.getKey()).append(" ").append(stat.getValue()).append("\n");
            }

            Profiler.instance().logEventAtTime(now, ProfilerTask.SANDBOX_MEMORY_INFO, stats.toString());
        }
    }
}
