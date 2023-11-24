package com.google.devtools.build.lib.packages;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParsingException;

import java.util.*;

/**
 * DATABRICKS ONLY: Represents a pool of Engflow Remote Execution worker pools.
 * Borrows heavily from {@link TestTimeout}.
 */
public class DatabricksEngflowTestPool {
    private final String pool;

    public DatabricksEngflowTestPool(String pool) {
        this.pool = pool;
    }

    public String getPool() {
        return pool;
    }

    public static class DatabricksEngflowTestPoolConverter extends Converter.Contextless<Map<TestSize, DatabricksEngflowTestPool>> {

        @Override
        public String getTypeDescription() {
            return "a single string or comma-separated list of 4 strings";
        }

        @Override
        public Map<TestSize, DatabricksEngflowTestPool> convert(String input) throws OptionsParsingException {
            List<DatabricksEngflowTestPool> values = new ArrayList<>();
            for (String token: Splitter.on(",").limit(6).split(input)) {
                if (!token.isEmpty() || values.size() > 1) {
                    values.add(new DatabricksEngflowTestPool(token));
                }
            }
            EnumMap<TestSize, DatabricksEngflowTestPool> pools = new EnumMap<>(TestSize.class);
            if (values.size() == 1) {
                pools.put(TestSize.SMALL, values.get(0));
                pools.put(TestSize.MEDIUM, values.get(0));
                pools.put(TestSize.LARGE, values.get(0));
                pools.put(TestSize.ENORMOUS, values.get(0));
            } else if (values.size() == 4) {
                pools.put(TestSize.SMALL, values.get(0));
                pools.put(TestSize.MEDIUM, values.get(1));
                pools.put(TestSize.LARGE, values.get(2));
                pools.put(TestSize.ENORMOUS, values.get(3));
            } else {
                throw new OptionsParsingException("Invalid number of comma-separated entries");
            }
            return pools;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatabricksEngflowTestPool that = (DatabricksEngflowTestPool) o;
        return Objects.equals(pool, that.pool);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pool);
    }
}
