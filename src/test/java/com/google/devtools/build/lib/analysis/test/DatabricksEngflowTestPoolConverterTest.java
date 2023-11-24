// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.test;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.packages.DatabricksEngflowTestPool;
import com.google.devtools.build.lib.packages.DatabricksEngflowTestPool.DatabricksEngflowTestPoolConverter;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A test for {@link DatabricksEngflowTestPoolConverter}.
 */
@RunWith(JUnit4.class)
public class DatabricksEngflowTestPoolConverterTest {
  private Map<TestSize, DatabricksEngflowTestPool> pools;

  protected void setPools(String option) throws OptionsParsingException {
    pools = new DatabricksEngflowTestPoolConverter().convert(option);
  }

  protected void assertPool(TestSize size, String expected) {
    assertThat(pools).containsEntry(size, new DatabricksEngflowTestPool(expected));
  }

  protected void assertFailure(String option) {
    assertThrows(
        "Incorrectly parsed '" + option + "'",
        OptionsParsingException.class,
        () -> setPools(option));
  }

  @Test
  public void testUniversalPool() throws Exception {
    setPools("same");
    assertPool(TestSize.SMALL, "same");
    assertPool(TestSize.MEDIUM, "same");
    assertPool(TestSize.LARGE, "same");
    assertPool(TestSize.ENORMOUS, "same");
  }

  @Test
  public void testSeparatePools() throws Exception {
    setPools("small,large,big,why");
    assertPool(TestSize.SMALL, "small");
    assertPool(TestSize.MEDIUM, "large");
    assertPool(TestSize.LARGE, "big");
    assertPool(TestSize.ENORMOUS, "why");
  }

  @Test
  public void testIncorrectStrings() {
    assertFailure("");
    assertFailure("1,2,3");
    assertFailure("1,2,,3,4");
    assertFailure("1,2,3 4");
    assertFailure("1,2,3,4,5");
  }
}
