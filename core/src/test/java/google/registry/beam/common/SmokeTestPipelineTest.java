// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.testing.DatabaseHelper.createTld;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.beam.TestPipelineExtension;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.FakeClock;
import java.time.Instant;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class SmokeTestPipelineTest {

  private final FakeClock clock = new FakeClock(Instant.parse("2021-02-02T00:00:05.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withClock(clock)
          .withProperty(AvailableSettings.ISOLATION, TRANSACTION_REPEATABLE_READ.name())
          .buildIntegrationTestExtension();

  @RegisterExtension
  final TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private final RegistryPipelineOptions options =
      PipelineOptionsFactory.create().as(RegistryPipelineOptions.class);

  @Test
  void whenIldsDoNotExist_failure() {
    var exception =
        assertThrows(
            Pipeline.PipelineExecutionException.class,
            () -> SmokeTestPipeline.runPipeline(options).waitUntilFinish());
    assertThat(exception).hasMessageThat().contains("Expecting 1 or more, got 0.");
  }

  @Test
  void whenTldsExist_success() {
    createTld("tld");
    SmokeTestPipeline.runPipeline(options).waitUntilFinish();
  }
}
