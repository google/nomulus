// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static google.registry.bigquery.BigqueryUtils.toBigqueryTimestamp;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.appengine.api.modules.ModulesService;
import com.google.auto.value.AutoValue;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.testing.AppEngineRule;
import google.registry.testing.InjectRule;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link BigQueryMetricsEnqueuer}. */
@RunWith(MockitoJUnitRunner.class)
public class BigQueryMetricsEnqueuerTest {

  @Rule
  public final InjectRule inject = new InjectRule();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withLocalModules()
      .withTaskQueue()
      .build();

  @Mock ModulesService modulesService;

  private BigQueryMetricsEnqueuer enqueuer;

  @Before
  public void setUp() {
    enqueuer = new BigQueryMetricsEnqueuer();
    enqueuer.idGenerator = Suppliers.ofInstance("laffo");
    enqueuer.modulesService = modulesService;
    enqueuer.bigQueryStreamingMetricsQueue = getQueue(BigQueryMetricsEnqueuer.QUEUE);
    when(modulesService.getVersionHostname(Matchers.anyString(), Matchers.anyString()))
        .thenReturn("1.backend.test.localhost");
  }

  @Test
  public void testExport() throws Exception {
    TestMetric metric =
        TestMetric.create(
            DateTime.parse("1984-12-18TZ"), DateTime.parse("1984-12-18TZ").plusMillis(1));

    enqueuer.export(metric);

    assertTasksEnqueued("bigquery-streaming-metrics",
        new TaskMatcher()
            .url("/_dr/task/metrics")
            .header("Host", "1.backend.test.localhost")
            .param("tableId", "test")
            .param("startTime", "472176000.000000")
            .param("endTime", "472176000.001000")
            .param("insertId", "laffo"));
  }

  /** A stub implementation of {@link BigQueryMetric}. */
  @AutoValue
  abstract static class TestMetric implements BigQueryMetric {

    static TestMetric create(DateTime startTimestamp, DateTime endTimestamp) {
      return new AutoValue_BigQueryMetricsEnqueuerTest_TestMetric(startTimestamp, endTimestamp);
    }

    @Override
    public String getTableId() {
      return "test";
    }

    @Override
    public ImmutableList<TableFieldSchema> getSchemaFields() {
      return null;
    }

    @Override
    public ImmutableMap<String, String> getBigQueryRowEncoding() {
      return ImmutableMap.of(
          "startTime", toBigqueryTimestamp(getStartTimestamp()),
          "endTime", toBigqueryTimestamp(getEndTimestamp()));
    }

    abstract DateTime getStartTimestamp();

    abstract DateTime getEndTimestamp();
  }
}
