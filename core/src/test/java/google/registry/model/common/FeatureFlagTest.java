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
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityTestCase;
import google.registry.model.common.FeatureFlag.FeatureFlagNotFoundException;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FeatureFlag}. */
public class FeatureFlagTest extends EntityTestCase {

  public FeatureFlagTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence() {
    FeatureFlag featureFlag =
        new FeatureFlag.Builder()
            .setFeatureName("testFlag")
            .setStatus(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(DateTime.now(UTC).plusWeeks(8), ACTIVE)
                    .build())
            .build();
    persistResource(featureFlag);
    FeatureFlag flagFromDb = FeatureFlag.get("testFlag");
    assertThat(featureFlag).isEqualTo(flagFromDb);
  }

  @Test
  void testFailure_featureFlagNotPresent() {
    FeatureFlagNotFoundException thrown =
        assertThrows(FeatureFlagNotFoundException.class, () -> FeatureFlag.get("fakeFlag"));
    assertThat(thrown).hasMessageThat().isEqualTo("No feature flag object(s) found for fakeFlag");
  }
}
