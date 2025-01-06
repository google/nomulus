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

import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_OPTIONAL;
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_PROHIBITED;
import static google.registry.model.common.FeatureFlag.FeatureName.TEST_FEATURE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.EntityYamlUtils;
import google.registry.model.common.FeatureFlag;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ListFeatureFlagsCommandTest extends CommandTestCase<ListFeatureFlagsCommand> {

  @BeforeEach
  void beforeEach() {
    command.mapper = EntityYamlUtils.createObjectMapper();
    fakeClock.setTo(DateTime.parse("1984-12-21T06:07:08.789Z"));
  }

  @Test
  void testSuccess_oneFlag() throws Exception {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(fakeClock.nowUtc().plusWeeks(8), ACTIVE)
                    .build())
            .build());
    runCommand();
    assertInStdout(loadFile(getClass(), "oneFlag.yaml"));
  }

  @Test
  void test_success_manyFlags() throws Exception {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(fakeClock.nowUtc().plusWeeks(8), ACTIVE)
                    .build())
            .build());
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(MINIMUM_DATASET_CONTACTS_OPTIONAL)
            .setStatusMap(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, INACTIVE)
                    .put(fakeClock.nowUtc().plusWeeks(1), ACTIVE)
                    .put(fakeClock.nowUtc().plusWeeks(8), INACTIVE)
                    .put(fakeClock.nowUtc().plusWeeks(10), ACTIVE)
                    .build())
            .build());
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(MINIMUM_DATASET_CONTACTS_PROHIBITED)
            .setStatusMap(
                ImmutableSortedMap.<DateTime, FeatureStatus>naturalOrder()
                    .put(START_OF_TIME, ACTIVE)
                    .build())
            .build());
    runCommand();
    assertInStdout(loadFile(getClass(), "threeFlags.yaml"));
  }
}
