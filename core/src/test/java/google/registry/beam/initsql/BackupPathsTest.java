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

package google.registry.beam.initsql;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.beam.initsql.BackupPaths.getCloudSQLCredentialFilePatterns;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link google.registry.beam.initsql.BackupPaths}. */
public class BackupPathsTest {

  @Test
  void getCloudSQLCredentialFilePatterns_alpha() {
    assertThat(getCloudSQLCredentialFilePatterns("domain-registry-alpha"))
        .containsExactly(
            "gs://domain-registry-dev-deploy/cloudsql-credentials/alpha/admin_credential.enc");
  }

  @Test
  void getCloudSQLCredentialFilePatterns_crash() {
    assertThat(getCloudSQLCredentialFilePatterns("domain-registry-crash"))
        .containsExactly(
            "gs://domain-registry-dev-deploy/cloudsql-credentials/crash/admin_credential.enc");
  }

  @Test
  void getCloudSQLCredentialFilePatterns_sandbox() {
    assertThat(getCloudSQLCredentialFilePatterns("domain-registry-sandbox"))
        .containsExactly(
            "gs://domain-registry-dev-deploy/cloudsql-credentials/sandbox/admin_credential.enc");
  }

  @Test
  void getCloudSQLCredentialFilePatterns_production() {
    assertThat(getCloudSQLCredentialFilePatterns("domain-registry"))
        .containsExactly(
            "gs://domain-registry-dev-deploy/cloudsql-credentials/production/admin_credential.enc");
  }

  @Test
  void getEnvFromProject_illegal() {
    assertThrows(
        IllegalArgumentException.class,
        () -> getCloudSQLCredentialFilePatterns("domain-registry-bad"));
  }

  @Test
  void getEnvFromProject_null() {
    assertThrows(IllegalArgumentException.class, () -> getCloudSQLCredentialFilePatterns(null));
  }
}
