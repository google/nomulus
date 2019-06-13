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

package google.registry.reporting;

import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import java.util.Map;
import org.joda.time.Duration;
import org.joda.time.YearMonth;

/** Static methods common to various reporting tasks. */
public class ReportingUtils {

  private static final int ENQUEUE_DELAY_MINUTES = 10;

  /** Enqueues a task that takes a Beam jobId and the {@link YearMonth} as parameters. */
  public static void enqueueBeamReportingTask(String path, Map<String, String> parameters) {
    TaskOptions publishTask =
        TaskOptions.Builder.withUrl(path)
            .method(TaskOptions.Method.POST)
            // Dataflow jobs tend to take about 10 minutes to complete.
            .countdownMillis(Duration.standardMinutes(ENQUEUE_DELAY_MINUTES).getMillis());
    parameters.forEach(publishTask::param);
    QueueFactory.getQueue(ReportingModule.BEAM_QUEUE).add(publishTask);
  }
}
