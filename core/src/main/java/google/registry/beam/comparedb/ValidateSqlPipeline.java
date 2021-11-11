// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Verify.verify;
import static org.apache.beam.sdk.values.TypeDescriptors.strings;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import google.registry.beam.common.DatabaseSnapshot;
import google.registry.beam.common.RegistryPipelineWorkerInitializer;
import google.registry.beam.comparedb.LatestDatastoreSnapshotFinder.DatastoreSnapshotInfo;
import google.registry.beam.comparedb.ValidateSqlUtils.CompareSqlEntity;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.replay.SqlEntity;
import google.registry.model.replay.SqlReplayCheckpoint;
import google.registry.persistence.PersistenceModule.JpaTransactionManagerType;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.TransactionManagerFactory;
import java.io.Serializable;
import java.util.Optional;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult.State;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.joda.time.DateTime;

/**
 * Validates the asynchronous data replication process from Datastore (primary storage) to Cloud SQL
 * (secondary storage).
 */
public class ValidateSqlPipeline {

  /** Specifies the extra CommitLogs to load before the start of a Database export. */
  private static final int COMMIT_LOG_MARGIN_MINUTES = 10;

  private final ValidateSqlPipelineOptions options;
  private final DatastoreSnapshotInfo mostRecentExport;

  public ValidateSqlPipeline(
      ValidateSqlPipelineOptions options, DatastoreSnapshotInfo mostRecentExport) {
    this.options = options;
    this.mostRecentExport = mostRecentExport;
  }

  void run() {
    run(Pipeline.create(options));
  }

  @VisibleForTesting
  void run(Pipeline pipeline) {
    // TODO(weiminyu): Acquire the commit log replay lock when the lock release bug is fixed.
    DateTime latestCommitLogTime =
        TransactionManagerFactory.jpaTm().transact(() -> SqlReplayCheckpoint.get());
    Preconditions.checkState(
        latestCommitLogTime.isAfter(mostRecentExport.exportInterval().getEnd()),
        "Cannot recreate Datastore snapshot since target time is in the middle of an export.");
    try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {
      setupPipeline(pipeline, Optional.of(databaseSnapshot.getSnapshotId()), latestCommitLogTime);
      State state = pipeline.run().waitUntilFinish();
      if (!State.DONE.equals(state)) {
        throw new IllegalStateException("Unexpected pipeline state: " + state);
      }
    }
  }

  void setupPipeline(
      Pipeline pipeline, Optional<String> sqlSnapshotId, DateTime latestCommitLogTime) {
    pipeline
        .getCoderRegistry()
        .registerCoderForClass(SqlEntity.class, SerializableCoder.of(Serializable.class));

    PCollectionTuple datastoreSnapshot =
        DatastoreSnapshots.loadDatastoreSnapshotByKind(
            pipeline,
            mostRecentExport.exportDir(),
            mostRecentExport.commitLogDir(),
            mostRecentExport.exportInterval().getStart().minusMinutes(COMMIT_LOG_MARGIN_MINUTES),
            // Increase by 1ms since we want to include commitLogs latestCommitLogTime but
            // this parameter is exclusive.
            latestCommitLogTime.plusMillis(1),
            DatastoreSnapshots.ALL_DATASTORE_KINDS);

    PCollectionTuple cloudSqlSnapshot =
        SqlSnapshots.loadCloudSqlSnapshotByType(
            pipeline, SqlSnapshots.ALL_SQL_ENTITIES, sqlSnapshotId);

    verify(
        datastoreSnapshot.getAll().keySet().equals(cloudSqlSnapshot.getAll().keySet()),
        "Expecting the same set of types in both snapshots.");

    for (Class<? extends SqlEntity> clazz : SqlSnapshots.ALL_SQL_ENTITIES) {
      TupleTag<SqlEntity> tag = ValidateSqlUtils.createSqlEntityTupleTag(clazz);
      verify(
          datastoreSnapshot.has(tag), "Missing %s in Datastore snapshot.", clazz.getSimpleName());
      verify(cloudSqlSnapshot.has(tag), "Missing %s in Cloud SQL snapshot.", clazz.getSimpleName());
      PCollectionList.of(datastoreSnapshot.get(tag))
          .and(cloudSqlSnapshot.get(tag))
          .apply("Combine from both snapshots: " + clazz.getSimpleName(), Flatten.pCollections())
          .apply(
              "Assign primary key to merged " + clazz.getSimpleName(),
              WithKeys.of(ValidateSqlPipeline::getPrimaryKeyString).withKeyType(strings()))
          .apply("Group by primary key " + clazz.getSimpleName(), GroupByKey.create())
          .apply("Compare " + clazz.getSimpleName(), ParDo.of(new CompareSqlEntity()));
    }
  }

  private static String getPrimaryKeyString(SqlEntity sqlEntity) {
    // SqlEntity.getPrimaryKeyString only works with entities registered with Hibernate.
    // We are using the BulkQueryJpaTransactionManager, which does not recognize DomainBase and
    // DomainHistory. See BulkQueryEntities.java for more information.
    if (sqlEntity instanceof DomainBase) {
      return "DomainBase_" + ((DomainBase) sqlEntity).getRepoId();
    }
    if (sqlEntity instanceof DomainHistory) {
      return "DomainHistory_" + ((DomainHistory) sqlEntity).getDomainHistoryId().toString();
    }
    return sqlEntity.getPrimaryKeyString();
  }

  public static void main(String[] args) {
    ValidateSqlPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(ValidateSqlPipelineOptions.class);

    // Defensively set important options.
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ);
    options.setJpaTransactionManagerType(JpaTransactionManagerType.BULK_QUERY);

    // Reuse Dataflow worker initialization code to set up JPA in the pipeline harness.
    new RegistryPipelineWorkerInitializer().beforeProcessing(options);

    DatastoreSnapshotInfo mostRecentExport =
        DaggerLatestDatastoreSnapshotFinder_LatestDatastoreSnapshotFinderFinderComponent.create()
            .datastoreSnapshotInfoFinder()
            .getSnapshotInfo();

    new ValidateSqlPipeline(options, mostRecentExport).run(Pipeline.create(options));
  }
}
