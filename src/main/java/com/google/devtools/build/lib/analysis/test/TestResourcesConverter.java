package com.google.devtools.build.lib.analysis.test;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.OptionsParsingException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class TestResourcesConverter extends Converter.Contextless<List<Map.Entry<String, Map<TestSize, Double>>>> {
    @Override
    public String getTypeDescription() {
      return "a resource name followed by equal and 1 float or 4 float, e.g memory=10,30,60,100";
    }

    @Override
    public List<Map.Entry<String, Map<TestSize, Double>>> convert(String input) throws OptionsParsingException {
      ImmutableList.Builder<Map.Entry<String, Map<TestSize, Double>>> resources = ImmutableList.builder();
      Map<String, String> requests;
      char resourcesSep = ':', valueSep = '=';

      try {
        requests = Splitter.on(resourcesSep).withKeyValueSeparator(valueSep).split(input);
      } catch (IllegalArgumentException e) {
        String message = String.format(
            "%s. Separate resources from each other with '%c' and resources"
              + " from their values with '%c', e.g. memory=10,20,50,100:cpu=1",
            e.getMessage(),
            resourcesSep,
            valueSep);
        throw new OptionsParsingException(message, e);
      }
      for (Map.Entry<String, String> request:  requests.entrySet()) {
        List<Double> values = new ArrayList<>();
        for (String token: Splitter.on(",").omitEmptyStrings().limit(5).split(request.getValue())) {
          try {
            values.add(Double.parseDouble(token));
          } catch (NumberFormatException e) {
            throw new OptionsParsingException("'" + token + "' is not a float", e);
          }
        }
        EnumMap<TestSize, Double> amounts = Maps.newEnumMap(TestSize.class);
        if (values.size() == 1) {
          amounts.put(TestSize.SMALL, values.get(0));
          amounts.put(TestSize.MEDIUM, values.get(0));
          amounts.put(TestSize.LARGE, values.get(0));
          amounts.put(TestSize.ENORMOUS, values.get(0));
        } else if (values.size() == 4) {
          amounts.put(TestSize.SMALL, values.get(0));
          amounts.put(TestSize.MEDIUM, values.get(1));
          amounts.put(TestSize.LARGE, values.get(2));
          amounts.put(TestSize.ENORMOUS, values.get(3));
        } else {
          throw new OptionsParsingException("Invalid number of comma-separated entries");
        }
        resources.add(Maps.immutableEntry(request.getKey(), amounts));
      }
      return resources.build();
    }
  }