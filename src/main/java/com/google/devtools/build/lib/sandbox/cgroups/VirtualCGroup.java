package com.google.devtools.build.lib.sandbox.cgroups;

import com.google.gson.stream.JsonWriter;
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
import com.google.devtools.build.lib.profiler.TraceData;
import com.google.devtools.build.lib.sandbox.cgroups.v1.LegacyCpu;
import com.google.devtools.build.lib.sandbox.cgroups.v1.LegacyCpuAcct;
import com.google.devtools.build.lib.sandbox.cgroups.v1.LegacyMemory;
import com.google.devtools.build.lib.sandbox.cgroups.v2.UnifiedCpu;
import com.google.devtools.build.lib.sandbox.cgroups.v2.UnifiedMemory;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;


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

    @Nullable
    private static volatile VirtualCGroup instance;

    public abstract Controller.Cpu cpu();
    public abstract Controller.Memory memory();
    @Nullable
    public abstract Controller.CpuAcct cpuacct();

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
                            memory = new UnifiedMemory(cgroup);
                            break;
                        case "cpu":
                            if (cpu != null) continue;
                            logger.atInfo().log("Found cgroup v2 cpu controller at %s", cgroup);
                            cpu = new UnifiedCpu(cgroup);
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
                            memory = new LegacyMemory(cgroup);
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
                    }
                }
            }
        }

        cpu = cpu != null ? cpu : Controller.getDefault(Controller.Cpu.class);
        memory = memory != null ? memory : Controller.getDefault(Controller.Memory.class);
        VirtualCGroup vcgroup = new AutoValue_VirtualCGroup(cpu, memory, cpuacct, paths.build());
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
        Controller.CpuAcct cpuacct = null;
        ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
        if (memory() != null && memory().getPath() != null) {
            copyControllersToSubtree(memory().getPath());
            Path cgroup = memory().getPath().resolve(name);
            cgroup.toFile().mkdirs();
            memory = memory().isLegacy() ? new LegacyMemory(cgroup) : new UnifiedMemory(cgroup);
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
        VirtualCGroup child = new AutoValue_VirtualCGroup(cpu, memory, cpuacct, paths.build());
        this.children.add(child);
        return child;
    }

    final class StatsData implements TraceData {
      @Override
      public void writeTraceData(JsonWriter jsonWriter, long profileStartTimeNanos) throws IOException {
        long timestamp = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - profileStartTimeNanos);
        if (cpu() != null || cpuacct() != null) {
          var stats = new LinkedHashMap<String, String>();
          if (cpu() != null) {
            try (BufferedReader reader = new BufferedReader(new StringReader(cpu().getStats()))) {
              String line;
              while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ", 2);
                stats.put(parts[0], parts[1]);
              }
            }
            stats.put("quota", String.valueOf(cpu().getCpus()));
            stats.put("period", String.valueOf(cpu().getPeriod()));
          }
          if (cpuacct() != null) {
            try (BufferedReader reader = new BufferedReader(new StringReader(cpuacct().getStats()))) {
              String line;
              while ((line = reader.readLine()) != null) {
                  String[] parts = line.split(" ", 2);
                  Double value = Long.parseLong(parts[1]) * 1e6 / LegacyCpuAcct.USER_HZ;
                  stats.put(parts[0] + "_usec", String.valueOf(value.longValue()));
              }
              }
              stats.put("usage_usec", String.valueOf(cpuacct().getUsage() / 1000));
          }

          writeStats(jsonWriter, timestamp, "CPU stats (Sandbox)",  stats);
        }
        if (memory() != null) {
          var stats = new LinkedHashMap<String, String>();
          Long kills = memory().oomKills();
          Long limit = memory().getMaxBytes();
          Long usage = memory().maxUsage();
          if (usage > 0) stats.put("max_usage_in_bytes", String.valueOf(usage));
          if (limit > 0) stats.put("limit_in_bytes", String.valueOf(limit));
          if (kills > 0) stats.put("oom_kills", String.valueOf(kills));
          writeStats(jsonWriter, timestamp, "Memory stats (Sandbox)", stats);
        }
      }

      void writeStats(JsonWriter writer, long timestamp, String name, Map<String, String> stats) throws IOException {
        var currentThread = Thread.currentThread();
        var threadId = currentThread.threadId();
        writer.setIndent("  ");
        writer.beginObject();
        writer.setIndent("");
        writer.name("cat").value("sandbox info");
        writer.name("name").value(name);
        writer.name("args");
        writer.beginObject();
        for (var entry : stats.entrySet()) {
          writer.name(entry.getKey()).value(entry.getValue());
        }
        writer.endObject();
        writer.name("ph").value("i");
        writer.name("ts").value(timestamp);
        writer.name("pid").value(1);
        writer.name("tid").value(threadId);
        writer.endObject();
      }
    }

    public void logStats() throws IOException {
        Profiler.instance().logData(new StatsData());
    }
}
