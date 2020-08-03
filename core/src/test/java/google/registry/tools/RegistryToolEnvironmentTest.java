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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.testing.SystemPropertyExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistryToolEnvironment}. */
class RegistryToolEnvironmentTest {

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @Test
  void testGet_withoutSetup_throws() {
    RegistryToolEnvironment.reset();
    assertThrows(IllegalStateException.class, RegistryToolEnvironment::get);
  }

  @Test
  void testSetup_changesEnvironmentReturnedByGet() {
    RegistryToolEnvironment.UNITTEST.setup(systemPropertyExtension);
    assertThat(RegistryToolEnvironment.get()).isEqualTo(RegistryToolEnvironment.UNITTEST);

    RegistryToolEnvironment.ALPHA.setup(systemPropertyExtension);
    assertThat(RegistryToolEnvironment.get()).isEqualTo(RegistryToolEnvironment.ALPHA);
  }

  @Test
  void testFromArgs_shortNotation_works() {
    assertThat(RegistryToolEnvironment.parseFromArgs(new String[] {"-e", "alpha"}))
        .isEqualTo(RegistryToolEnvironment.ALPHA);
  }

  @Test
  void testFromArgs_longNotation_works() {
    assertThat(RegistryToolEnvironment.parseFromArgs(new String[] {"--environment", "alpha"}))
        .isEqualTo(RegistryToolEnvironment.ALPHA);
  }

  @Test
  void testFromArgs_uppercase_works() {
    assertThat(RegistryToolEnvironment.parseFromArgs(new String[] {"-e", "QA"}))
        .isEqualTo(RegistryToolEnvironment.QA);
  }

  @Test
  void testFromArgs_equalsNotation_works() {
    assertThat(RegistryToolEnvironment.parseFromArgs(new String[] {"-e=sandbox"}))
        .isEqualTo(RegistryToolEnvironment.SANDBOX);
    assertThat(RegistryToolEnvironment.parseFromArgs(new String[] {"--environment=sandbox"}))
        .isEqualTo(RegistryToolEnvironment.SANDBOX);
  }

  @Test
  void testFromArgs_envFlagAfterCommandName_getsIgnored() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RegistryToolEnvironment.parseFromArgs(
                new String[] {"registrar_activity_report", "-e", "1406851199"}));
  }

  @Test
  void testFromArgs_missingEnvironmentFlag_throwsIae() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RegistryToolEnvironment.parseFromArgs(new String[] {}));
  }

  @Test
  void testFromArgs_extraEnvFlagAfterCommandName_getsIgnored() {
    String[] args = new String[] {"-e", "alpha", "registrar_activity_report", "-e", "1406851199"};
    assertThat(RegistryToolEnvironment.parseFromArgs(args))
        .isEqualTo(RegistryToolEnvironment.ALPHA);
  }

  @Test
  void testFromArgs_loggingFlagWithUnderscores_isntConsideredCommand() {
    String[] args =
        new String[] {
          "--logging_properties_file", "my_file.properties",
          "-e", "alpha",
          "list_tlds"
        };
    assertThat(RegistryToolEnvironment.parseFromArgs(args))
        .isEqualTo(RegistryToolEnvironment.ALPHA);
  }

  @Test
  void testFromArgs_badName_throwsIae() {
    assertThrows(
        IllegalArgumentException.class,
        () -> RegistryToolEnvironment.parseFromArgs(new String[] {"-e", "alphaville"}));
  }
}
