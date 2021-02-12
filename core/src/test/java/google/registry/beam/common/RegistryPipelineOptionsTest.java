// Copyright 2020 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.beam.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.beam.common.RegistryPipelineOptions.validateRegistryPipelineOptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.config.RegistryEnvironment;
import google.registry.testing.SystemPropertyExtension;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistryPipelineOptions}. */
class RegistryPipelineOptionsTest {

  /**
   * Restores original RegistryEnvironment after calling {@link
   * RegistryPipelineOptions#validateRegistryPipelineOptions}.
   */
  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @BeforeEach
  void beforeEach() {
    // Saves the current RegistryEnvironment for restoration later.
    RegistryEnvironment.get().setup(systemPropertyExtension);
  }

  @Test
  void environment() {
    assertThat(
            PipelineOptionsFactory.fromArgs("--registryEnvironment=ALPHA")
                .as(RegistryPipelineOptions.class)
                .getRegistryEnvironment())
        .isSameInstanceAs(RegistryEnvironment.ALPHA);
  }

  @Test
  void environment_invalid() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PipelineOptionsFactory.fromArgs("--registryEnvironment=alpha")
                .as(RegistryPipelineOptions.class));
  }

  @Test
  void environment_undefined() {
    assertThat(
            PipelineOptionsFactory.create()
                .as(RegistryPipelineOptions.class)
                .getRegistryEnvironment())
        .isNull();
  }

  @Test
  void validateProject_projectsMatch() {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(
                "--registryEnvironment=" + RegistryEnvironment.UNITTEST.name(),
                "--project=registry-project-id")
            .withValidation()
            .as(RegistryPipelineOptions.class);
    validateRegistryPipelineOptions(options);
  }

  @Test
  void validateProject_projectsMismatch() {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(
                "--registryEnvironment=" + RegistryEnvironment.UNITTEST.name(), "--project=")
            .withValidation()
            .as(RegistryPipelineOptions.class);
    assertThrows(IllegalArgumentException.class, () -> validateRegistryPipelineOptions(options));
  }

  @Test
  void validateProject_missingProjectAdded() {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(
                "--registryEnvironment=" + RegistryEnvironment.UNITTEST.name())
            .withValidation()
            .as(RegistryPipelineOptions.class);
    // If gcloud is installed and --project not set , project may be inferred.
    options.setProject(null);
    validateRegistryPipelineOptions(options);
    assertThat(options.getProject()).isEqualTo("registry-project-id");
  }

  @Test
  void validateProject_noEnvironment() {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs("--project=some-project").as(RegistryPipelineOptions.class);
    validateRegistryPipelineOptions(options);
    assertThat(options.getProject()).isEqualTo("some-project");
  }
}
