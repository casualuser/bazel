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

package com.google.devtools.build.benchmark;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.benchmark.codegenerator.JavaCodeGenerator;
import com.google.devtools.build.lib.shell.CommandException;
import java.io.IOException;
import java.nio.file.Path;

/** Class for running a build group with all build targets and getting performance results. */
class BuildGroupRunner {

  private static final String GENERATED_CODE_FOR_COPY_DIR = "GeneratedCodeForCopy";
  private static final String GENERATED_CODE_DIR = "GeneratedCode";
  private static final String BUILDER_DIR = "BuilderBazel";
  private static final int REPEAT_TIMES = 3;

  private final Path workspace;
  private Builder builder = null;

  BuildGroupRunner(Path workspace) {
    this.workspace = workspace;
  }

  BuildGroupResult run(String from, String to) throws IOException, CommandException {
    BuildCase buildCase = new BazelBuildCase();
    ImmutableList<BuildTargetConfig> buildTargetConfigs = buildCase.getBuildTargetConfigs();
    ImmutableList<BuildEnvConfig> buildEnvConfigs = buildCase.getBuildEnvConfigs();

    // Prepare builder (Bazel)
    prepareBuilder();
    System.out.println("Done preparing builder.");

    ImmutableList<String> codeVersions = buildCase.getCodeVersions(builder, from, to);

    BuildGroupResult.Builder buildGroupResultBuilder =
        getBuildGroupResultBuilder(buildTargetConfigs, buildEnvConfigs, codeVersions);

    boolean lastIsIncremental = true;
    for (int versionIndex = 0; versionIndex < codeVersions.size(); ++versionIndex) {
      String version = codeVersions.get(versionIndex);
      System.out.format("Benchmark for version %s started.\n", version);

      // Get builder binary (build Bazel binary)
      Path buildBinary = buildBinary = builder.getBuildBinary(version);

      for (int t = 0; t < REPEAT_TIMES; ++t) {
        for (int envIndex = 0; envIndex < buildEnvConfigs.size(); ++envIndex) {
          BuildEnvConfig envConfig = buildEnvConfigs.get(envIndex);
          System.out.println("Started config: " + envConfig.getDescription());

          for (int targetIndex = 0; targetIndex < buildTargetConfigs.size(); ++targetIndex) {
            BuildTargetConfig targetConfig = buildTargetConfigs.get(targetIndex);
            System.out.println(targetConfig.getDescription());

            // Prepare generated code for build
            if (lastIsIncremental && !envConfig.getIncremental()) {
              buildCase.prepareGeneratedCode(
                  workspace.resolve(GENERATED_CODE_FOR_COPY_DIR),
                  workspace.resolve(GENERATED_CODE_DIR));
            }
            if (!lastIsIncremental && envConfig.getIncremental()) {
              JavaCodeGenerator.modifyExistingProject(
                  workspace.resolve(GENERATED_CODE_DIR).toString(), true, true, true, true);
            }
            lastIsIncremental = envConfig.getIncremental();

            // Builder's clean method
            if (envConfig.getCleanBeforeBuild()) {
              builder.clean();
            }

            // Run build
            double elapsedTime =
                builder.buildAndGetElapsedTime(
                    buildBinary, builder.getCommandFromConfig(targetConfig, envConfig));
            System.out.println(elapsedTime);

            // Store result
            buildGroupResultBuilder
                .getBuildTargetResultsBuilder(targetIndex)
                .getBuildEnvResultsBuilder(envIndex)
                .getResultsBuilder(versionIndex)
                .addResults(elapsedTime);
          }
        }
      }
    }

    return buildGroupResultBuilder.build();
  }

  private static BuildGroupResult.Builder getBuildGroupResultBuilder(
      ImmutableList<BuildTargetConfig> buildTargetConfigs,
      ImmutableList<BuildEnvConfig> buildEnvConfigs,
      ImmutableList<String> codeVersions) {
    // Initialize a BuildGroupResult object to preserve array length
    BuildGroupResult.Builder buildGroupResultBuilder = BuildGroupResult.newBuilder();
    for (BuildTargetConfig targetConfig : buildTargetConfigs) {
      BuildTargetResult.Builder targetBuilder =
          BuildTargetResult.newBuilder().setBuildTargetConfig(targetConfig);
      prepareBuildEnvConfigs(buildEnvConfigs, codeVersions, targetBuilder);
      buildGroupResultBuilder.addBuildTargetResults(targetBuilder.build());
    }
    return buildGroupResultBuilder;
  }

  private static void prepareBuildEnvConfigs(
      ImmutableList<BuildEnvConfig> buildEnvConfigs,
      ImmutableList<String> codeVersions,
      BuildTargetResult.Builder targetBuilder) {
    for (BuildEnvConfig envConfig : buildEnvConfigs) {
      BuildEnvResult.Builder envBuilder = BuildEnvResult.newBuilder().setConfig(envConfig);
      for (String version : codeVersions) {
        envBuilder.addResults(SingleBuildResult.newBuilder().setCodeVersion(version).build());
      }
      targetBuilder.addBuildEnvResults(envBuilder.build());
    }
  }

  private void prepareBuilder() throws IOException, CommandException {
    builder =
        new BazelBuilder(workspace.resolve(GENERATED_CODE_DIR), workspace.resolve(BUILDER_DIR));
    builder.prepare();
  }
}