package com.google.devtools.build.lib.sandbox.cgroups;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Monitor {
    private static String PREFIX = "max_mon_";

    private volatile boolean active;

    private final HashMap<String, Long> max;
    private final Controller controller;
    private final ExecutorService executor;

    interface Monitorable {
        Monitor monitor();
    }

    public static class MonitorFactory {
        private final ThreadPoolExecutor executor;

        public MonitorFactory() {
            this.executor = new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                10,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactoryBuilder().setNameFormat("cgroup-%d").build());
        }

        public Monitor create(Controller controller) {
            return new Monitor(controller, executor);
        }


    }

    private Monitor(Controller contoller, ExecutorService executor) {
        this.controller = contoller;
        this.executor = executor;
        this.max = new HashMap<>();
    }

    public void start(List<String> fields) {
        if (fields.isEmpty()) {
            return;
        }
        start(Duration.ofSeconds(1), ImmutableSet.copyOf(fields));
    }

    private void start(Duration interval, ImmutableSet<String> fields) {
        Preconditions.checkArgument(!active, "Monitoring started twice");
        active = true;
        executor.execute(() -> {
            synchronized (this.max) {
                while (active) {
                    try {
                        for (String stat : controller.getStats().split("\n")) {
                            String[] parts = stat.split(" ", 2);
                            if (parts.length < 2) continue;
                            Long value = Long.parseLong(parts[1]);
                            if (value > 0 && fields.contains(parts[0])) {
                                Long current = max.getOrDefault(PREFIX + parts[0], 0L);
                                max.put(PREFIX + parts[0], Math.max(current, value));
                            }
                        }
                        Thread.sleep(interval.toMillis());
                    } catch (IOException | InterruptedException e) {
                        break;
                    }
                }
            }
        });
    }

    public ImmutableMap<String, Long> stop() {
        this.active = false;
        synchronized (this.max) {
          return ImmutableMap.copyOf(this.max);
        }
    }
}