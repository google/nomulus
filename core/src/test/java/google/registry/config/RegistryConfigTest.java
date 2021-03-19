// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.config;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.config.RegistryConfig.ConfigModule;

import dagger.Component;
import google.registry.config.RegistryConfig.Config;
import javax.inject.Singleton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RegistryConfig}. */
class RegistryConfigTest {

  private static RegistryConfigTestComponent registryConfig;

  @BeforeAll
  static void beforeAll() {
    registryConfig = DaggerRegistryConfigTest_RegistryConfigTestComponent.create();
  }

  @Test
  void reservedTermsExportDisclaimer_isPrependedWithOctothorpes() {
    assertThat(registryConfig.reservedTermsExportDisclaimer())
        .isEqualTo("# Disclaimer line 1.\n" + "# Line 2 is this 1.");
  }

  @Test
  void beamStagedTemplateBucketUrl_default() {
    assertThat(registryConfig.beamStagedTemplateBucketUrl())
        .isEqualTo("gs://registry-project-id-deploy/live/beam");
  }

  @Component(modules = {ConfigModule.class})
  @Singleton
  interface RegistryConfigTestComponent {

    @Config("beamStagedTemplateBucketUrl")
    String beamStagedTemplateBucketUrl();

    @Config("reservedTermsExportDisclaimer")
    String reservedTermsExportDisclaimer();
  }
}
