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
import static google.registry.config.RegistryConfig.CONFIG_SETTINGS;
import static google.registry.config.RegistryConfig.ConfigModule.provideReservedTermsExportDisclaimer;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link RegistryConfig}. */
class RegistryConfigTest {

  @Test
  void test_reservedTermsExportDisclaimer_isPrependedWithOctothorpes() {
    assertThat(provideReservedTermsExportDisclaimer(CONFIG_SETTINGS.get()))
        .isEqualTo("# Disclaimer line 1.\n" + "# Line 2 is this 1.");
  }
}
