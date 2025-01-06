// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_OPTIONAL;
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_PROHIBITED;
import static google.registry.model.common.FeatureFlag.FeatureName.TEST_FEATURE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.FeatureFlag;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ConfigureFeatureFlagCommand}. */
public class ConfigureFeatureFlagCommandTest extends CommandTestCase<ConfigureFeatureFlagCommand> {

  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01T00:00:00Z"));

  @Test
  void testCreate() throws Exception {
    DateTime featureStart = clock.nowUtc().plusWeeks(2);

    runCommandForced(
        "TEST_FEATURE",
        "--status_map",
        String.format("%s=INACTIVE,%s=ACTIVE", START_OF_TIME, featureStart));

    assertThat(FeatureFlag.get(TEST_FEATURE).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, featureStart, ACTIVE)));
    assertThat(FeatureFlag.getAllUncached()).hasSize(1);
  }

  @Test
  void testCreate_multipleFlags() throws Exception {
    DateTime featureStart = clock.nowUtc().plusWeeks(2);

    runCommandForced(
        "TEST_FEATURE",
        "MINIMUM_DATASET_CONTACTS_OPTIONAL",
        "MINIMUM_DATASET_CONTACTS_PROHIBITED",
        "--status_map",
        String.format("%s=INACTIVE,%s=ACTIVE", START_OF_TIME, featureStart));

    assertThat(FeatureFlag.get(TEST_FEATURE).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, featureStart, ACTIVE)));
    assertThat(FeatureFlag.get(MINIMUM_DATASET_CONTACTS_OPTIONAL).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, featureStart, ACTIVE)));
    assertThat(FeatureFlag.get(MINIMUM_DATASET_CONTACTS_PROHIBITED).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, featureStart, ACTIVE)));
    assertThat(FeatureFlag.getAllUncached()).hasSize(3);
  }

  @Test
  void testUpdate() throws Exception {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .build())
            .build());

    DateTime featureStart = clock.nowUtc().plusWeeks(6);
    assertThat(FeatureFlag.get(TEST_FEATURE).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(ImmutableSortedMap.of(START_OF_TIME, INACTIVE)));
    assertThat(FeatureFlag.getAllUncached()).hasSize(1);

    runCommandForced(
        "TEST_FEATURE",
        "--status_map",
        String.format("%s=INACTIVE,%s=ACTIVE", START_OF_TIME, featureStart));

    assertThat(FeatureFlag.get(TEST_FEATURE).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, featureStart, ACTIVE)));
    assertThat(FeatureFlag.getAllUncached()).hasSize(1);
  }

  @Test
  void testConfigure_multipleFlags() throws Exception {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .build())
            .build());

    DateTime featureStart = clock.nowUtc().plusWeeks(6);
    assertThat(FeatureFlag.get(TEST_FEATURE).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(ImmutableSortedMap.of(START_OF_TIME, INACTIVE)));
    assertThat(FeatureFlag.getAllUncached()).hasSize(1);

    runCommandForced(
        "TEST_FEATURE",
        "MINIMUM_DATASET_CONTACTS_OPTIONAL",
        "MINIMUM_DATASET_CONTACTS_PROHIBITED",
        "--status_map",
        String.format("%s=INACTIVE,%s=ACTIVE", START_OF_TIME, featureStart));

    assertThat(FeatureFlag.get(TEST_FEATURE).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, featureStart, ACTIVE)));
    assertThat(FeatureFlag.get(MINIMUM_DATASET_CONTACTS_OPTIONAL).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, featureStart, ACTIVE)));
    assertThat(FeatureFlag.get(MINIMUM_DATASET_CONTACTS_PROHIBITED).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, featureStart, ACTIVE)));
    assertThat(FeatureFlag.getAllUncached()).hasSize(3);
  }

  @Test
  void testCreate_invalidFeatureName() throws Exception {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "INVALID_NAME", "--status_map", String.format("%s=ACTIVE", START_OF_TIME)));

    assertThat(thrown)
        .hasMessageThat()
        .contains("Invalid value for [Main class] parameter. Allowed values");
  }

  @Test
  void testCreate_invalidStatusMap() throws Exception {
    DateTime featureStart = clock.nowUtc().plusWeeks(2);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "TEST_FEATURE", "--status_map", String.format("%s=ACTIVE", featureStart)));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must provide transition entry for the start of time (Unix Epoch)");
  }

  @Test
  void testUpdate_invalidStatusMap() throws Exception {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .build())
            .build());

    DateTime featureStart = clock.nowUtc().plusWeeks(6);
    assertThat(FeatureFlag.get(TEST_FEATURE).getStatusMap())
        .isEqualTo(
            TimedTransitionProperty.fromValueMap(ImmutableSortedMap.of(START_OF_TIME, INACTIVE)));
    assertThat(FeatureFlag.getAllUncached()).hasSize(1);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "TEST_FEATURE", "--status_map", String.format("%s=ACTIVE", featureStart)));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must provide transition entry for the start of time (Unix Epoch)");
  }
}
