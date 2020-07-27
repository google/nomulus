// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;

import google.registry.monitoring.whitebox.CheckApiMetric.Status;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CheckApiMetric}. */
class CheckApiMetricTest {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private final FakeClock clock = new FakeClock(START_TIME);
  private CheckApiMetric.Builder metricBuilder;

  @BeforeEach
  void beforeEach() {
    metricBuilder = CheckApiMetric.builder(clock);
  }

  @Test
  void testSuccess_timestampsAreSet() {
    clock.advanceOneMilli();
    CheckApiMetric metric = metricBuilder.status(Status.SUCCESS).build();

    assertThat(metric.startTimestamp()).isEqualTo(START_TIME);
    assertThat(metric.endTimestamp()).isEqualTo(clock.nowUtc());
  }
}
