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

import google.registry.config.RegistryEnvironment;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.options.Description;

/** Defines Nomulus-specific pipeline options. */
public interface RegistryPipelineOptions extends GcpOptions {

  @Description("The Registry environment.")
  @Nullable
  RegistryEnvironment getRegistryEnvironment();

  void setRegistryEnvironment(RegistryEnvironment environment);

  /**
   * Validates the GCP project and Registry environment settings in {@code option}. If project is
   * undefined, it is set according to the Registry environment; if project is defined but
   * inconsistent with the Registry environment, an {@link IllegalArgumentException} will be thrown.
   *
   * <p>This method may modify the system property ("google.registry.environment" which is defined
   * in {@link RegistryEnvironment}). Tests calling this method must restore the original
   * environment on completion.
   */
  static void validateRegistryPipelineOptions(RegistryPipelineOptions option) {
    RegistryEnvironment environment = option.getRegistryEnvironment();
    if (environment == null) {
      return;
    }
    environment.setup();
    String projectByEnv = DaggerRegistryPipelineComponent.create().getProjectId();
    if (Objects.equals(option.getProject(), projectByEnv)) {
      return;
    }
    if (option.getProject() == null) {
      option.setProject(projectByEnv);
      return;
    }
    throw new IllegalArgumentException(
        "Arguments for --project and --registryEnvironment do not match.");
  }
}
