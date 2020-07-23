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

package google.registry.monitoring.whitebox;

import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;

import com.google.common.collect.ImmutableSet;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link EppMetric}. */
class EppMetricTest {

  @RegisterExtension
  final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Test
  void test_invalidTld_isRecordedAsInvalid() {
    EppMetric metric =
        EppMetric.builderForRequest(new FakeClock())
            .setTlds(ImmutableSet.of("notarealtld"))
            .build();
    assertThat(metric.getTld()).hasValue("_invalid");
  }

  @Test
  void test_validTld_isRecorded() {
    createTld("example");
    EppMetric metric =
        EppMetric.builderForRequest(new FakeClock()).setTlds(ImmutableSet.of("example")).build();
    assertThat(metric.getTld()).hasValue("example");
  }

  @Test
  void test_multipleTlds_areRecordedAsVarious() {
    createTlds("foo", "bar");
    EppMetric metric =
        EppMetric.builderForRequest(new FakeClock())
            .setTlds(ImmutableSet.of("foo", "bar", "baz"))
            .build();
    assertThat(metric.getTld()).hasValue("_various");
  }

  @Test
  void test_zeroTlds_areRecordedAsAbsent() {
    EppMetric metric =
        EppMetric.builderForRequest(new FakeClock()).setTlds(ImmutableSet.of()).build();
    assertThat(metric.getTld()).isEmpty();
  }
}
