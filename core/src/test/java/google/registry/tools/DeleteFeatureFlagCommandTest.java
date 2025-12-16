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
import static google.registry.model.common.FeatureFlag.FeatureName.TEST_FEATURE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.FeatureFlag;
import org.junit.jupiter.api.Test;

/** Tests for {@link DeleteFeatureFlagCommand}. */
public class DeleteFeatureFlagCommandTest extends CommandTestCase<DeleteFeatureFlagCommand> {

  @Test
  void testSimpleSuccess() throws Exception {
    persistResource(
        new FeatureFlag()
            .asBuilder()
            .setFeatureName(TEST_FEATURE)
            .setStatusMap(ImmutableSortedMap.of(START_OF_TIME, ACTIVE))
            .build());
    assertThat(tm().transact(() -> FeatureFlag.isActiveNow(TEST_FEATURE))).isTrue();
    runCommandForced("TEST_FEATURE");
    assertThat(FeatureFlag.getUncached(TEST_FEATURE)).isEmpty();
  }

  @Test
  void testSuccess_noLongerPartOfEnum() throws Exception {
    tm().transact(
            () ->
                tm().getEntityManager()
                    .createNativeQuery(
                        "INSERT INTO \"FeatureFlag\" VALUES('nonexistent',"
                            + " '\"1970-01-01T00:00:00.000Z\"=>\"INACTIVE\"')")
                    .executeUpdate());
    assertThat(
            tm().transact(
                    () ->
                        tm().query(
                                "SELECT COUNT(*) FROM FeatureFlag WHERE featureName ="
                                    + " 'nonexistent'",
                                long.class)
                            .getSingleResult()))
        .isEqualTo(1L);
    runCommandForced("nonexistent");
    assertThat(
            tm().transact(
                    () ->
                        tm().query(
                                "SELECT COUNT(*) FROM FeatureFlag WHERE featureName ="
                                    + " 'nonexistent'",
                                long.class)
                            .getSingleResult()))
        .isEqualTo(0L);
  }

  @Test
  void testFailure_nonExistent() throws Exception {
    runCommandForced("nonexistent");
    assertInStdout("No flag found with name 'nonexistent'");
  }
}
