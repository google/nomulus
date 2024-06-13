// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityYamlUtils;
import google.registry.model.common.FeatureFlag;
import google.registry.model.common.FeatureFlag.FeatureFlagNotFoundException;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetFeatureFlagCommand}. */
public class GetFeatureFlagCommandTest extends CommandTestCase<GetFeatureFlagCommand> {

  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01T00:00:00Z"));

  @BeforeEach
  void beforeEach() {
    command.objectMapper = EntityYamlUtils.createObjectMapper();
  }

  @Test
  void testSuccess() throws Exception {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName("testFlag")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(clock.nowUtc().plusWeeks(8), ACTIVE)
                    .build())
            .build());
    runCommand("testFlag");
    assertInStdout(
        "featureName: \"testFlag\"\n"
            + "status:\n"
            + "  \"1970-01-01T00:00:00.000Z\": \"INACTIVE\"\n"
            + "  \"2000-02-26T00:00:00.000Z\": \"ACTIVE\"");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName("testFlag")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(clock.nowUtc().plusWeeks(8), ACTIVE)
                    .build())
            .build());
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName("secondFlag")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(clock.nowUtc().plusWeeks(3), ACTIVE)
                    .put(clock.nowUtc().plusWeeks(6), INACTIVE)
                    .build())
            .build());
    runCommand("testFlag", "secondFlag");
    assertInStdout(
        "featureName: \"testFlag\"\n"
            + "status:\n"
            + "  \"1970-01-01T00:00:00.000Z\": \"INACTIVE\"\n"
            + "  \"2000-02-26T00:00:00.000Z\": \"ACTIVE\""
            + "\n\n"
            + "featureName: \"secondFlag\"\n"
            + "status:\n"
            + "  \"1970-01-01T00:00:00.000Z\": \"INACTIVE\"\n"
            + "  \"2000-01-22T00:00:00.000Z\": \"ACTIVE\"\n"
            + "  \"2000-02-12T00:00:00.000Z\": \"INACTIVE\"");
  }

  @Test
  void testFailure_featureFlagDoesNotExist() {
    FeatureFlagNotFoundException thrown =
        assertThrows(FeatureFlagNotFoundException.class, () -> runCommand("nullFlag"));
    assertThat(thrown).hasMessageThat().isEqualTo("No feature flag object(s) found for nullFlag");
  }

  @Test
  void testFailure_oneFlagDoesNotExist() {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName("testFlag")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(clock.nowUtc().plusWeeks(8), ACTIVE)
                    .build())
            .build());
    assertThrows(FeatureFlagNotFoundException.class, () -> runCommand("testFlag", "missingFlag"));
  }

  @Test
  void testFailure_noTldName() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
