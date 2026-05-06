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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_OPTIONAL;
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_PROHIBITED;
import static google.registry.model.common.FeatureFlag.FeatureName.TEST_FEATURE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityTestCase;
import google.registry.model.common.FeatureFlag.FeatureFlagNotFoundException;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FeatureFlag}. */
public class FeatureFlagTest extends EntityTestCase {

  public FeatureFlagTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testSuccess_persistence() {
    FeatureFlag featureFlag =
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, INACTIVE)
                    .put(fakeClock.now().plus(Duration.ofDays(56)), ACTIVE)
                    .build())
            .build();
    persistResource(featureFlag);
    FeatureFlag flagFromDb = loadByEntity(featureFlag);
    assertThat(featureFlag).isEqualTo(flagFromDb);
    assertThat(featureFlag.getFeatureName()).isEqualTo(TEST_FEATURE);
  }

  @Test
  void testSuccess_getSingleFlag() {
    FeatureFlag featureFlag =
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, INACTIVE)
                    .put(fakeClock.now().plus(Duration.ofDays(56)), ACTIVE)
                    .build())
            .build();
    persistResource(featureFlag);
    assertThat(FeatureFlag.get(TEST_FEATURE)).isEqualTo(featureFlag);
  }

  @Test
  void testSuccess_getMultipleFlags() {
    FeatureFlag featureFlag1 =
        persistResource(
            new FeatureFlag.Builder()
                .setFeatureName(TEST_FEATURE)
                .setStatusMap(
                    ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                        .put(START_INSTANT, INACTIVE)
                        .put(fakeClock.now().plus(Duration.ofDays(56)), ACTIVE)
                        .build())
                .build());
    FeatureFlag featureFlag2 =
        persistResource(
            new FeatureFlag.Builder()
                .setFeatureName(MINIMUM_DATASET_CONTACTS_OPTIONAL)
                .setStatusMap(
                    ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                        .put(START_INSTANT, INACTIVE)
                        .put(fakeClock.now().plus(Duration.ofDays(21)), INACTIVE)
                        .build())
                .build());
    FeatureFlag featureFlag3 =
        persistResource(
            new FeatureFlag.Builder()
                .setFeatureName(MINIMUM_DATASET_CONTACTS_PROHIBITED)
                .setStatusMap(
                    ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                        .put(START_INSTANT, INACTIVE)
                        .build())
                .build());
    ImmutableSet<FeatureFlag> featureFlags =
        FeatureFlag.getAll(
            ImmutableSet.of(
                TEST_FEATURE,
                MINIMUM_DATASET_CONTACTS_OPTIONAL,
                MINIMUM_DATASET_CONTACTS_PROHIBITED));
    assertThat(featureFlags.size()).isEqualTo(3);
    assertThat(featureFlags).containsExactly(featureFlag1, featureFlag2, featureFlag3);
  }

  @Test
  void testFailure_getMultipleFlagsOneMissing() {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, INACTIVE)
                    .put(fakeClock.now().plus(Duration.ofDays(56)), ACTIVE)
                    .build())
            .build());
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(MINIMUM_DATASET_CONTACTS_OPTIONAL)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, INACTIVE)
                    .put(fakeClock.now().plus(Duration.ofDays(21)), INACTIVE)
                    .build())
            .build());
    FeatureFlagNotFoundException thrown =
        assertThrows(
            FeatureFlagNotFoundException.class,
            () ->
                FeatureFlag.getAll(
                    ImmutableSet.of(
                        TEST_FEATURE,
                        MINIMUM_DATASET_CONTACTS_OPTIONAL,
                        MINIMUM_DATASET_CONTACTS_PROHIBITED)));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("No feature flag object(s) found for MINIMUM_DATASET_CONTACTS_PROHIBITED");
  }

  @Test
  void testFailure_featureFlagNotPresent() {
    FeatureFlagNotFoundException thrown =
        assertThrows(FeatureFlagNotFoundException.class, () -> FeatureFlag.get(TEST_FEATURE));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("No feature flag object(s) found for TEST_FEATURE");
  }

  @Test
  void testFailure_nullFeatureName() {
    FeatureFlag.Builder featureFlagBuilder =
        new FeatureFlag.Builder()
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, INACTIVE)
                    .put(fakeClock.now().plus(Duration.ofDays(56)), ACTIVE)
                    .build());
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> featureFlagBuilder.build());
    assertThat(thrown).hasMessageThat().isEqualTo("FeatureName cannot be null");
  }

  @Test
  void testFailure_invalidStatusMap() {
    FeatureFlag.Builder featureFlagBuilder = new FeatureFlag.Builder().setFeatureName(TEST_FEATURE);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                featureFlagBuilder.setStatusMap(
                    ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                        .put(fakeClock.now().plus(Duration.ofDays(56)), ACTIVE)
                        .build()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must provide transition entry for the start of time (Unix Epoch)");
  }

  @Test
  void testSuccess_isActiveNow() {
    fakeClock.setTo(Instant.parse("2010-10-17T00:00:00Z"));
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, INACTIVE)
                    .put(fakeClock.now().plus(Duration.ofDays(56)), ACTIVE)
                    .build())
            .build());
    assertThat(tm().transact(() -> FeatureFlag.isActiveNow(TEST_FEATURE))).isFalse();
    fakeClock.setTo(Instant.parse("2011-10-17T00:00:00Z"));
    assertThat(tm().transact(() -> FeatureFlag.isActiveNow(TEST_FEATURE))).isTrue();
  }

  @Test
  void testSuccess_default_exists() {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<Instant, FeatureStatus>naturalOrder()
                    .put(START_INSTANT, INACTIVE)
                    .put(fakeClock.now().plus(Duration.ofDays(56)), ACTIVE)
                    .build())
            .build());
    tm().transact(
            () -> {
              assertThat(FeatureFlag.isActiveNow(TEST_FEATURE)).isFalse();
            });
    fakeClock.advanceBy(Duration.ofDays(365));
    tm().transact(
            () -> {
              assertThat(FeatureFlag.isActiveNow(TEST_FEATURE)).isTrue();
            });
  }
}
