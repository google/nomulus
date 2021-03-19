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

import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import google.registry.bigquery.BigqueryUtils.SourceFormat;
import google.registry.export.ExportConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Command to load Datastore snapshots into Bigquery. */
@Parameters(separators = " =", commandDescription = "Load Datastore snapshot into Bigquery")
final class LoadSnapshotCommand extends BigqueryCommand {

  @Parameter(
      names = "--snapshot",
      description = "Common filename prefix of the specific snapshot series to import.")
  private String snapshotPrefix = null;

  @Parameter(
      names = "--gcs_bucket",
      description = "Name of the GCS bucket from which to import Datastore snapshots.")
  private String snapshotGcsBucket = "domain-registry/snapshots/testing";

  @Parameter(
      names = "--kinds",
      description = "List of Datastore kinds for which to import snapshot data.")
  private List<String> kindNames = new ArrayList<>(ExportConstants.getReportingKinds());

  /** Runs the main snapshot import logic. */
  @Override
  public void runWithBigquery() throws Exception {
    kindNames.removeAll(ImmutableList.of(""));  // Filter out any empty kind names.
    if (snapshotPrefix == null || kindNames.isEmpty()) {
      System.err.println("Nothing to import; specify --snapshot and at least one kind.");
      return;
    }
    Map<String, ListenableFuture<?>> loadJobs = loadSnapshotKinds(kindNames);
    waitForLoadJobs(loadJobs);
  }

  /**
   * Starts load jobs for the given snapshot kinds, and returns a map of kind name to
   * ListenableFuture representing the result of the load job for that kind.
   */
  private Map<String, ListenableFuture<?>> loadSnapshotKinds(List<String> kindNames) {
    ImmutableMap.Builder<String, ListenableFuture<?>> builder = new ImmutableMap.Builder<>();
    for (String kind : kindNames) {
      String filename = String.format(
          "gs://%s/%s.%s.backup_info", snapshotGcsBucket, snapshotPrefix, kind);
      builder.put(kind, loadSnapshotFile(filename, kind));
      System.err.println("Started load job for kind: " + kind);
    }
    return builder.build();
  }

  /** Starts a load job for the specified kind name, sourcing data from the given GCS file. */
  private ListenableFuture<?> loadSnapshotFile(String filename, String kindName) {
    return bigquery()
        .startLoad(
            bigquery()
                .buildDestinationTable(kindName)
                .description("Datastore snapshot import for " + kindName + ".")
                .build(),
            SourceFormat.DATASTORE_BACKUP,
            ImmutableList.of(filename));
  }

  /**
   * Block on the completion of the load jobs in the provided map, printing out information on
   * each job's success or failure.
   */
  private void waitForLoadJobs(Map<String, ListenableFuture<?>> loadJobs) throws Exception {
    final long startTime = System.currentTimeMillis();
    System.err.println("Waiting for load jobs...");
    // Add callbacks to each load job that print information on successful completion or failure.
    for (final String jobId : loadJobs.keySet()) {
      final String jobName = "load-" + jobId;
      addCallback(
          loadJobs.get(jobId),
          new FutureCallback<Object>() {
            private double elapsedSeconds() {
              return (System.currentTimeMillis() - startTime) / 1000.0;
            }

            @Override
            public void onSuccess(Object unused) {
              System.err.printf("Job %s succeeded (%.3fs)\n", jobName, elapsedSeconds());
            }

            @Override
            public void onFailure(Throwable error) {
              System.err.printf(
                  "Job %s failed (%.3fs): %s\n", jobName, elapsedSeconds(), error.getMessage());
            }
          },
          directExecutor());
    }
    // Block on the completion of all the load jobs.
    List<?> results = Futures.successfulAsList(loadJobs.values()).get();
    int numSucceeded = (int) results.stream().filter(Objects::nonNull).count();
    System.err.printf(
        "All load jobs have terminated: %d/%d successful.\n",
        numSucceeded, loadJobs.size());
  }
}
