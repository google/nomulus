// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.comparedb;

import google.registry.beam.common.RegistryPipelineOptions;
import google.registry.beam.common.RegistryPipelineWorkerInitializer;
import google.registry.beam.comparedb.LatestDatastoreSnapshotFinder.DatastoreSnapshotInfo;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.persistence.PersistenceModule.JpaTransactionManagerType;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import java.util.Optional;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.joda.time.DateTime;

/**
 * Validates the asynchronous data replication process from Cloud SQL (primary) to Datastore
 * (secondary).
 *
 * <p>This pipeline simply compares the snapshots provided by an invoker, which is responsible for
 * obtaining two consistent snapshots for the same point of time.
 */
// TODO(weiminyu): Implement the invoker action in a followup PR.
@DeleteAfterMigration
public class ValidateDatastorePipeline {

  private final ValidateDatastorePipelineOptions options;
  private final LatestDatastoreSnapshotFinder datastoreSnapshotFinder;

  public ValidateDatastorePipeline(
      ValidateDatastorePipelineOptions options,
      LatestDatastoreSnapshotFinder datastoreSnapshotFinder) {
    this.options = options;
    this.datastoreSnapshotFinder = datastoreSnapshotFinder;
  }

  void run(Pipeline pipeline) {
    DateTime latestCommitLogTime = DateTime.parse(options.getLatestCommitLogTimestamp());
    DatastoreSnapshotInfo mostRecentExport =
        datastoreSnapshotFinder.getSnapshotInfo(latestCommitLogTime.toInstant());

    ValidateSqlPipeline.setupPipeline(
        pipeline,
        Optional.ofNullable(options.getSqlSnapshotId()),
        mostRecentExport,
        latestCommitLogTime,
        Optional.ofNullable(options.getComparisonStartTimestamp()).map(DateTime::parse));

    pipeline.run();
  }

  public static void main(String[] args) {
    ValidateDatastorePipelineOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(ValidateDatastorePipelineOptions.class);
    RegistryPipelineOptions.validateRegistryPipelineOptions(options);

    // Defensively set important options.
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ);
    options.setJpaTransactionManagerType(JpaTransactionManagerType.BULK_QUERY);

    // Reuse Dataflow worker initialization code to set up JPA in the pipeline harness.
    new RegistryPipelineWorkerInitializer().beforeProcessing(options);

    LatestDatastoreSnapshotFinder datastoreSnapshotFinder =
        DaggerLatestDatastoreSnapshotFinder_LatestDatastoreSnapshotFinderFinderComponent.create()
            .datastoreSnapshotInfoFinder();
    new ValidateDatastorePipeline(options, datastoreSnapshotFinder).run(Pipeline.create(options));
  }
}
