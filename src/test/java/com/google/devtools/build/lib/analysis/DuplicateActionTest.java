//// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis;

import static org.junit.Assert.assertFalse;

import com.google.devtools.build.lib.analysis.util.AnalysisTestCaseForJunit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for duplicate action detection and handling when incremental analysis is enabled.
 */
@RunWith(JUnit4.class)
public class DuplicateActionTest extends AnalysisTestCaseForJunit4 {

  @Test
  public void testDuplicateBuildInfoHeaderAction() throws Exception {
    scratch.file("a/stamp.cc",
        "// Empty."
    );
    scratch.file("a/BUILD",
        "cc_binary(name = 'a', srcs = ['a.cc'], deps = [':c'], stamp = 1)",
        "cc_binary(name = 'b', srcs = ['b.cc'], deps = [':c'], stamp = 1)",
        "cc_library(name = 'c', linkstamp = 'stamp.cc')");
    update("//a:a", "//a:b");
    assertFalse(hasErrors(getConfiguredTarget("//a:a")));
    assertFalse(hasErrors(getConfiguredTarget("//a:b")));
  }

  /**
   *  Same test with loading phase disabled.
   */
  @RunWith(JUnit4.class)
  public static class DuplicateActionTestWithoutLoading extends DuplicateActionTest {

    @Override
    protected boolean isLoadingEnabled() {
      return false;
    }
  }
}
