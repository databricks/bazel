// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.escape.CharEscaper;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.DeterministicWriter;
import com.google.devtools.build.lib.analysis.actions.PathMappers;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext.CommandLineCcCompilationContext;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Creates C++ module map artifact genfiles. These are then passed to Clang to do dependency
 * checking.
 */
@Immutable
public final class CppModuleMapAction extends AbstractFileWriteAction {
  public static final String MNEMONIC = "CppModuleMap";

  private static final String GUID = "4f407081-1951-40c1-befc-d6b4daff5de3";
  private static final Interner<ImmutableSortedMap<String, String>> executionInfoInterner =
      BlazeInterners.newWeakInterner();

  // C++ module map of the current target
  private final CppModuleMap cppModuleMap;

  /**
   * If set, the paths in the module map are relative to the current working directory instead of
   * relative to the module map file's location.
   */
  private final boolean moduleMapHomeIsCwd;

  // Data required to build the actual module map.
  // NOTE: If you add a field here, you'll likely need to add it to the cache key in computeKey().
  private final ImmutableList<Artifact> privateHeaders;
  private final ImmutableList<Artifact> publicHeaders;
  private final ImmutableList<CppModuleMap> dependencies;
  private final ImmutableList<PathFragment> additionalExportedHeaders;
  private final ImmutableList<Artifact> separateModuleHeaders;
  private final boolean compiledModule;
  private final boolean generateSubmodules;
  private final boolean externDependencies;
  private final ImmutableSortedMap<String, String> executionInfo;

  // databricks-extension {
  private final CommandLineCcCompilationContext cmdLineCtx;

  // todo(petrk): this breaks @Immutable
  private CcToolchainVariables ccToolchainVariables = null;
  public synchronized void provideCcToolchainVars(CcToolchainVariables vars) {
    if (this.ccToolchainVariables == null) {
      this.ccToolchainVariables = vars;
    }
  }
  // databricks-extension }

  public CppModuleMapAction(
      ActionOwner owner,
      CppModuleMap cppModuleMap,
      Iterable<Artifact> privateHeaders,
      Iterable<Artifact> publicHeaders,
      Iterable<CppModuleMap> dependencies,
      Iterable<PathFragment> additionalExportedHeaders,
      Iterable<Artifact> separateModuleHeaders,
      boolean compiledModule,
      boolean moduleMapHomeIsCwd,
      boolean generateSubmodules,
      boolean externDependencies,
      OutputPathsMode outputPathsMode,
      ImmutableMap<String, String> executionInfo
  ) {
    this(
        owner, cppModuleMap, privateHeaders, publicHeaders,
        dependencies, additionalExportedHeaders, separateModuleHeaders,
        compiledModule, moduleMapHomeIsCwd, generateSubmodules,
        externDependencies, outputPathsMode, executionInfo,
        CommandLineCcCompilationContext.EMPTY_CONTEXT
    );
  }

  public CppModuleMapAction(
      ActionOwner owner,
      CppModuleMap cppModuleMap,
      Iterable<Artifact> privateHeaders,
      Iterable<Artifact> publicHeaders,
      Iterable<CppModuleMap> dependencies,
      Iterable<PathFragment> additionalExportedHeaders,
      Iterable<Artifact> separateModuleHeaders,
      boolean compiledModule,
      boolean moduleMapHomeIsCwd,
      boolean generateSubmodules,
      boolean externDependencies,
      OutputPathsMode outputPathsMode,
      ImmutableMap<String, String> executionInfo,
      CommandLineCcCompilationContext cmdLineCtx
      ) {
    super(
        owner,
        NestedSetBuilder.<Artifact>stableOrder()
            .addAll(Iterables.filter(privateHeaders, Artifact::isTreeArtifact))
            .addAll(Iterables.filter(publicHeaders, Artifact::isTreeArtifact))
            .build(),
        cppModuleMap.getArtifact());
    this.cppModuleMap = cppModuleMap;
    this.moduleMapHomeIsCwd = moduleMapHomeIsCwd;
    this.privateHeaders = ImmutableList.copyOf(privateHeaders);
    this.publicHeaders = ImmutableList.copyOf(publicHeaders);
    this.dependencies = ImmutableList.copyOf(dependencies);
    this.additionalExportedHeaders = ImmutableList.copyOf(additionalExportedHeaders);
    this.separateModuleHeaders = ImmutableList.copyOf(separateModuleHeaders);
    this.compiledModule = compiledModule;
    this.generateSubmodules = generateSubmodules;
    this.externDependencies = externDependencies;
    // Save memory by storing outputPathsMode implicitly via the presence of
    // ExecutionRequirements.SUPPORTS_PATH_MAPPING in the key set. Path mapping is only effectively
    // enabled if the key is present *and* the mode is set to STRIP, so if the latter is not the
    // case, we can safely not store the key.
    Map<String, String> storedExecutionInfo;
    if (outputPathsMode == OutputPathsMode.STRIP) {
      storedExecutionInfo = executionInfo;
    } else {
      storedExecutionInfo =
          Maps.filterKeys(
              executionInfo, k -> !k.equals(ExecutionRequirements.SUPPORTS_PATH_MAPPING));
    }
    this.executionInfo =
        storedExecutionInfo.isEmpty()
            ? ImmutableSortedMap.of()
            : executionInfoInterner.intern(ImmutableSortedMap.copyOf(storedExecutionInfo));
    // databricks-extension {
    this.cmdLineCtx = cmdLineCtx;
    // databricks-extension }
  }

  @Override
  public boolean makeExecutable() {
    // In theory, module maps should not be executable but, in practice, we don't care. As
    // 'executable' is the default (see ActionOutputMetadataStore.setPathReadOnlyAndExecutable()),
    // we want to avoid the extra file operation of making this file non-executable.
    // Note that the opposite is true for Bazel: making a file executable results in an extra file
    // operation in com.google.devtools.build.lib.exec.FileWriteStrategy.
    return true;
  }

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
    final ArtifactExpander artifactExpander = ctx.getArtifactExpander();
    // TODO: It is possible that compile actions consuming the module map have path mapping disabled
    //  due to inputs conflicting across configurations. Since these inputs aren't inputs of the
    //  module map action, the generated map still contains mapped paths, which then results in
    //  compilation failures. This should be very rare as #include doesn't allow to disambiguate
    //  between headers from different configurations but with identical root-relative paths.
    final PathMapper pathMapper =
        PathMappers.create(this, getOutputPathsMode(), /* isStarlarkAction= */ false);
    return out -> {
      OutputStreamWriter content = new OutputStreamWriter(out, StandardCharsets.ISO_8859_1);
      PathFragment fragment = pathMapper.map(cppModuleMap.getArtifact().getExecPath());
      int segmentsToExecPath = fragment.segmentCount() - 1;
      Optional<Artifact> umbrellaHeader = cppModuleMap.getUmbrellaHeader();
      String leadingPeriods = moduleMapHomeIsCwd ? "" : "../".repeat(segmentsToExecPath);

      Iterable<Artifact> separateModuleHdrs =
          expandedHeaders(artifactExpander, separateModuleHeaders);

      // For details about the different header types, see:
      // http://clang.llvm.org/docs/Modules.html#header-declaration
      content.append("module \"").append(cppModuleMap.getName()).append("\" {\n");
      content.append("  export *\n");

      HashSet<PathFragment> deduper = new HashSet<>();
      if (umbrellaHeader.isPresent()) {
        appendHeader(
            content,
            "",
            umbrellaHeader.get().getExecPath(),
            leadingPeriods,
            /* canCompile= */ false,
            deduper,
            /*isUmbrellaHeader*/ true,
            pathMapper);
      } else {
        for (Artifact artifact : expandedHeaders(artifactExpander, publicHeaders)) {
          appendHeader(
              content,
              "",
              artifact.getExecPath(),
              leadingPeriods,
              /* canCompile= */ true,
              deduper,
              /*isUmbrellaHeader*/ false,
              pathMapper);
        }
        for (Artifact artifact : expandedHeaders(artifactExpander, privateHeaders)) {
          appendHeader(
              content,
              "private",
              artifact.getExecPath(),
              leadingPeriods,
              /* canCompile= */ true,
              deduper,
              /*isUmbrellaHeader*/ false,
              pathMapper);
        }
        for (Artifact artifact : separateModuleHdrs) {
          appendHeader(
              content,
              "",
              artifact.getExecPath(),
              leadingPeriods,
              /* canCompile= */ false,
              deduper,
              /*isUmbrellaHeader*/ false,
              pathMapper);
        }
        for (PathFragment additionalExportedHeader : additionalExportedHeaders) {
          appendHeader(
              content,
              "",
              additionalExportedHeader,
              leadingPeriods,
              /*canCompile*/ false,
              deduper,
              /*isUmbrellaHeader*/ false,
              pathMapper);
        }
      }
      for (CppModuleMap dep : dependencies) {
        content.append("  use \"").append(dep.getName()).append("\"\n");
      }

      // databricks-extension {
      final BiConsumer<String, Iterable<PathFragment>> appendIncludeDirs = (opt, values) -> {
        try {
          for (PathFragment value : values) {
            if (!value.isEmpty()) {
              content.append("  command_line_option \"").append(opt).append("\"\n");
              content.append("  command_line_option \"").append(value.getPathString()).append("\"\n");
            }
          }
        }
        catch (IOException ex) {
          throw new RuntimeException(ex);
        }
      };
      appendIncludeDirs.accept("-iquote", cmdLineCtx.quoteIncludeDirs);
      appendIncludeDirs.accept("-I", cmdLineCtx.includeDirs);
      appendIncludeDirs.accept("-isystem", cmdLineCtx.systemIncludeDirs);
//      for (var define : cmdLineCtx.defines) {
//        content.append("  command_line_option \"-D").append(JavaStringEscaper.escapeString(define)).append("\"\n");
//      }
//      for (var define : cmdLineCtx.localDefines) {
//        content.append("  command_line_option \"-D").append(JavaStringEscaper.escapeString(define)).append("\"\n");
//      }
      try {
        final var vars = ccToolchainVariables.getSequenceVariable("user_compile_flags", pathMapper);
        for (var variable : vars) {
          final var value = variable.getStringValue("", pathMapper);
          if (value != null) {
            if (value.startsWith("-D")) {
              content.append("  command_line_option \"").append(JavaStringEscaper.escapeString(value)).append("\"\n");
            } else if (value.startsWith("-I")) {
              content.append("  command_line_option \"").append(JavaStringEscaper.escapeString(value)).append("\"\n");
            }
          }
        }
      } catch (CcToolchainFeatures.ExpansionException ex) {
        throw new RuntimeException(ex);
      }
      // databricks-extension }

      if (!Iterables.isEmpty(separateModuleHdrs)) {
        String separateName = cppModuleMap.getName() + CppModuleMap.SEPARATE_MODULE_SUFFIX;
        content.append("  use \"").append(separateName).append("\"\n");
        content.append("}\n");
        content.append("module \"").append(separateName).append("\" {\n");
        content.append("  export *\n");
        deduper = new HashSet<>();
        for (Artifact artifact : separateModuleHdrs) {
          appendHeader(
              content,
              "",
              artifact.getExecPath(),
              leadingPeriods,
              /* canCompile= */ true,
              deduper,
              /*isUmbrellaHeader*/ false,
              pathMapper);
        }
        for (CppModuleMap dep : dependencies) {
          content.append("  use \"").append(dep.getName()).append("\"\n");
        }
      }
      content.append("}");

      if (externDependencies) {
        for (CppModuleMap dep : dependencies) {
          content
              .append("\nextern module \"")
              .append(dep.getName())
              .append("\" \"")
              .append(leadingPeriods)
              .append(pathMapper.getMappedExecPathString(dep.getArtifact()))
              .append("\"");
        }
      }
      content.flush();
    };
  }

  private static ImmutableList<Artifact> expandedHeaders(
      ArtifactExpander artifactExpander, Iterable<Artifact> unexpandedHeaders) {
    List<Artifact> expandedHeaders = new ArrayList<>();
    for (Artifact unexpandedHeader : unexpandedHeaders) {
      if (unexpandedHeader.isTreeArtifact()) {
        artifactExpander.expand(unexpandedHeader, expandedHeaders);
      } else {
        expandedHeaders.add(unexpandedHeader);
      }
    }

    return ImmutableList.copyOf(expandedHeaders);
  }

  private void appendHeader(
      Appendable content,
      String visibilitySpecifier,
      PathFragment unmappedPath,
      String leadingPeriods,
      boolean canCompile,
      Set<PathFragment> deduper,
      boolean isUmbrellaHeader,
      PathMapper pathMapper)
      throws IOException {
    PathFragment path = pathMapper.map(unmappedPath);
    if (deduper.contains(path)) {
      return;
    }
    deduper.add(path);
    if (isUmbrellaHeader) {
      content.append("  umbrella header \"umbrella.h\"\n");
      return;
    }
    if (generateSubmodules) {
      content.append("  module \"").append(path.toString()).append("\" {\n");
      content.append("    export *\n  ");
    }
    content.append("  ");
    if (!visibilitySpecifier.isEmpty()) {
      content.append(visibilitySpecifier).append(" ");
    }
    if (!canCompile || !shouldCompileHeader(path)) {
      content.append("textual ");
    }
    content.append("header \"").append(leadingPeriods).append(path.toString()).append("\"");
    if (generateSubmodules) {
      content.append("\n  }");
    }
    content.append("\n");
  }

  // databricks-extension {
  private static final String DB_TEXTUAL_HEADERS_FILE = System.getenv().getOrDefault("DB_TEXTUAL_HEADERS_FILE", "");

  private static final List<Pattern> DB_TEXTUAL_HEADERS;
  static {
      var patterns = new ArrayList<Pattern>();
      if (!DB_TEXTUAL_HEADERS_FILE.isBlank()) {
          Path dbTextualHeadersPath = DB_TEXTUAL_HEADERS_FILE.startsWith("~/")
                  ? Path.of(System.getenv("user.home"), DB_TEXTUAL_HEADERS_FILE.substring("~/".length()))
                  : Path.of(DB_TEXTUAL_HEADERS_FILE);
          try (var lines = Files.lines(dbTextualHeadersPath)) {
              patterns.addAll(lines.filter(line -> !line.isBlank()).map(Pattern::compile).toList());
          }
          catch (IOException ex) {
              ex.printStackTrace();
          }
      }
      DB_TEXTUAL_HEADERS = Collections.unmodifiableList(patterns);
  }

  private static boolean isDbTextualHeader(PathFragment path) {
    for (var headerPattern : DB_TEXTUAL_HEADERS) {
      if (headerPattern.matcher(path.getPathString()).matches()) {
        return true;
      }
    }
    return false;
  }
  // databricks-extension }

  private boolean shouldCompileHeader(PathFragment path) {
    // databricks-changed:
    return compiledModule && !CppFileTypes.CPP_TEXTUAL_INCLUDE.matches(path) && !isDbTextualHeader(path);
  }

  @Override
  public String getMnemonic() {
    return MNEMONIC;
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable Artifact.ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    fp.addString(GUID);
    fp.addInt(privateHeaders.size());
    for (Artifact artifact : privateHeaders) {
      fp.addPath(artifact.getExecPath());
    }
    fp.addInt(publicHeaders.size());
    for (Artifact artifact : publicHeaders) {
      fp.addPath(artifact.getExecPath());
    }
    fp.addInt(separateModuleHeaders.size());
    for (Artifact artifact : separateModuleHeaders) {
      fp.addPath(artifact.getExecPath());
    }
    fp.addInt(dependencies.size());
    for (CppModuleMap dep : dependencies) {
      fp.addString(dep.getName());
      fp.addPath(dep.getArtifact().getExecPath());
    }
    fp.addInt(additionalExportedHeaders.size());
    for (PathFragment path : additionalExportedHeaders) {
      fp.addPath(path);
    }
    fp.addPath(cppModuleMap.getArtifact().getExecPath());
    Optional<Artifact> umbrellaHeader = cppModuleMap.getUmbrellaHeader();
    if (umbrellaHeader.isPresent()) {
      fp.addPath(umbrellaHeader.get().getExecPath());
    }
    fp.addString(cppModuleMap.getName());
    fp.addBoolean(moduleMapHomeIsCwd);
    fp.addBoolean(compiledModule);
    fp.addBoolean(generateSubmodules);
    fp.addBoolean(externDependencies);
    PathMappers.addToFingerprint(
        getMnemonic(),
        getExecutionInfo(),
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        actionKeyContext,
        getOutputPathsMode(),
        fp);
  }

  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return executionInfo;
  }

  private OutputPathsMode getOutputPathsMode() {
    // See comment in the constructor for how outputPathsMode is stored implicitly.
    return executionInfo.containsKey(ExecutionRequirements.SUPPORTS_PATH_MAPPING)
        ? OutputPathsMode.STRIP
        : OutputPathsMode.OFF;
  }

  @VisibleForTesting
  public CppModuleMap getCppModuleMap() {
    return cppModuleMap;
  }

  @VisibleForTesting
  public ImmutableList<Artifact> getPublicHeaders() {
    return publicHeaders;
  }

  @VisibleForTesting
  public ImmutableList<Artifact> getPrivateHeaders() {
    return privateHeaders;
  }

  @VisibleForTesting
  public ImmutableList<PathFragment> getAdditionalExportedHeaders() {
    return additionalExportedHeaders;
  }

  @VisibleForTesting
  public ImmutableList<Artifact> getSeparateModuleHeaders() {
    return separateModuleHeaders;
  }

  @VisibleForTesting
  public Collection<Artifact> getDependencyArtifacts() {
    List<Artifact> artifacts = new ArrayList<>();
    for (CppModuleMap map : dependencies) {
      artifacts.add(map.getArtifact());
    }
    return artifacts;
  }
}

@Immutable
final class JavaStringEscaper extends CharEscaper {
  public static final JavaStringEscaper INSTANCE = new JavaStringEscaper();

  private static final CharMatcher UNSAFECHAR_MATCHER =
      CharMatcher.anyOf("\"").precomputed();

  @Override
  @Nullable
  public char[] escape(char c) {
    if (!UNSAFECHAR_MATCHER.matches(c)) {
      return null;
    } else {
      char[] result = new char[2];
      result[0] = '\\';
      result[1] = c;
      return result;
    }
  }

  public static String escapeString(String unescaped) {
    return INSTANCE.escape(unescaped);
  }
}