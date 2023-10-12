package com.google.devtools.build.lib.sandbox.cgroups;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Monitor {
    private static String PREFIX = "max_mon_";
    private boolean active;
    private final Map<String, Long> max;
    private final Controller controller;

    public Monitor(Controller contoller) {
        this.controller = contoller;
        this.max = new HashMap<>();
    }

    public void start(int interval, ImmutableSet<String> fields)
            throws IOException, InterruptedException, IllegalStateException {
        if (fields.isEmpty()) {
            return;
        }
        if (active) {
            throw new IllegalStateException("Monitoring started twice");
        }
        active = true;
        new Thread(() -> {
          while (active) {
            try {
              for (String stat : controller.getStats().split("\n")) {
                String[] parts = stat.split(" ", 2);
                Long value = Long.parseLong(parts[1]);
                if (value > 0 && fields.contains(parts[0])) {
                  Long current = max.getOrDefault(PREFIX + parts[0], 0L);

                  max.put(PREFIX + parts[0], Math.max(current, value));
                }
              }
              Thread.sleep(interval * 1000L);
            } catch (IOException | InterruptedException e) {
              break;
            }
          }
        }).start();
    }

    public ImmutableMap<String, Long> stop() {
        this.active = false;
        return ImmutableMap.copyOf(this.max);
    }
}
