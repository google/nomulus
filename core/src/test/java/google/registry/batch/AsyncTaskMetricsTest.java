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

package google.registry.batch;

import static com.google.monitoring.metrics.contrib.DistributionMetricSubject.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;
import static google.registry.batch.AsyncTaskMetrics.OperationResult.SUCCESS;
import static google.registry.batch.AsyncTaskMetrics.OperationType.CONTACT_AND_HOST_DELETE;

import com.google.common.collect.ImmutableSet;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AsyncTaskMetrics}. */
class AsyncTaskMetricsTest {

  private final FakeClock clock = new FakeClock();
  private final AsyncTaskMetrics asyncTaskMetrics = new AsyncTaskMetrics(clock);

  @Test
  void testRecordAsyncFlowResult_calculatesDurationMillisCorrectly() {
    asyncTaskMetrics.recordAsyncFlowResult(
        CONTACT_AND_HOST_DELETE,
        SUCCESS,
        clock.nowUtc().minusMinutes(10).minusSeconds(5).minusMillis(566));
    assertThat(AsyncTaskMetrics.asyncFlowOperationCounts)
        .hasValueForLabels(1, "contactAndHostDelete", "success")
        .and()
        .hasNoOtherValues();
    assertThat(AsyncTaskMetrics.asyncFlowOperationProcessingTime)
        .hasDataSetForLabels(ImmutableSet.of(605566.0), "contactAndHostDelete", "success")
        .and()
        .hasNoOtherValues();
  }
}
