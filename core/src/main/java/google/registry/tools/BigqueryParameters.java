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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.bigquery.BigqueryConnection;
import java.util.concurrent.Executors;
import org.joda.time.Duration;

/** Parameter delegate class to handle flag settings for a command's BigqueryConnection object. */
@Parameters(separators = " =")
final class BigqueryParameters {

  /**
   * Default to 20 threads to stay within Bigquery's rate limit of 20 concurrent queries.
   *
   * @see <a href="https://cloud.google.com/bigquery/quota-policy">BigQuery Quota Policy</a>
   */
  private static final int DEFAULT_NUM_THREADS = 20;

  @Parameter(
      names = "--bigquery_dataset",
      description = "Name of the default dataset to use, for reading and writing.")
  private String bigqueryDataset = BigqueryConnection.DEFAULT_DATASET_NAME;

  @Parameter(
      names = "--bigquery_overwrite",
      description = "Whether to automatically overwrite existing tables and views.")
  private boolean bigqueryOverwrite;

  @Parameter(
      names = "--bigquery_poll_interval",
      description = "Interval in milliseconds to wait between polls for job status.")
  private Duration bigqueryPollInterval = Duration.standardSeconds(1);

  @Parameter(
      names = "--bigquery_num_threads",
      description = "Number of threads for running simultaneous BigQuery operations.")
  private int bigqueryNumThreads = DEFAULT_NUM_THREADS;

  /** Returns a new BigqueryConnection constructed according to the delegate's flag settings. */
  BigqueryConnection newConnection(BigqueryConnection.Builder connectionBuilder) {
    return connectionBuilder
        .setExecutorService(Executors.newFixedThreadPool(bigqueryNumThreads))
        .setDatasetId(bigqueryDataset)
        .setOverwrite(bigqueryOverwrite)
        .setPollInterval(bigqueryPollInterval)
        .build();
  }
}
