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

import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;

import com.google.appengine.api.modules.ModulesService;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.common.base.Supplier;
import google.registry.util.FormattingLogger;
import java.util.Map.Entry;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * A collector of metric information. Enqueues collected metrics to a task queue to be written to
 * BigQuery asynchronously.
 *
 * @see MetricsExportAction
 */
public class BigQueryMetricsEnqueuer {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  public static final String QUEUE = "bigquery-streaming-metrics";

  @Inject ModulesService modulesService;
  @Inject @Named(QUEUE) Queue bigQueryStreamingMetricsQueue;
  @Inject @Named("insertIdGenerator") Supplier<String> idGenerator;

  @Inject BigQueryMetricsEnqueuer() {}

  public void export(BigQueryMetric metric) {
    try {
      String hostname = modulesService.getVersionHostname("backend", null);
      TaskOptions opts =
          withUrl(MetricsExportAction.PATH)
              .header("Host", hostname)
              .param("insertId", idGenerator.get());
      for (Entry<String, String> entry : metric.getBigQueryRowEncoding().entrySet()) {
        opts.param(entry.getKey(), entry.getValue());
      }
      opts.param("tableId", metric.getTableId());
      bigQueryStreamingMetricsQueue.add(opts);
    } catch (TransientFailureException e) {
      // Log and swallow. We may drop some metrics here but this should be rare.
      logger.info(e, e.getMessage());
    }
  }
}
