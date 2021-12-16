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
import com.google.common.base.Stopwatch;
import com.google.common.flogger.FluentLogger;
import google.registry.beam.common.DatabaseSnapshot;
import google.registry.beam.common.RegistryPipelineWorkerInitializer;
import google.registry.beam.comparedb.LatestDatastoreSnapshotFinder.DatastoreSnapshotInfo;
import google.registry.beam.comparedb.ValidateSqlUtils.CompareSqlEntity;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.replay.SqlEntity;
import google.registry.model.replay.SqlReplayCheckpoint;
import google.registry.model.server.Lock;
import google.registry.persistence.PersistenceModule.JpaTransactionManagerType;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.util.RequestStatusChecker;
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
import org.joda.time.Duration;

/**
 * Validates the asynchronous data replication process from Datastore (primary storage) to Cloud SQL
 * (secondary storage).
 */
@DeleteAfterMigration
public class ValidateSqlPipeline {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Specifies the extra CommitLogs to load before the start of a Database export. */
  private static final Duration COMMITLOG_START_TIME_MARGIN = Duration.standardMinutes(10);

  /**
   * Name of the lock used by the commitlog replay process.
   *
   * <p>See {@link google.registry.backup.ReplayCommitLogsToSqlAction} for more information.
   */
  private static final String COMMITLOG_REPLAY_LOCK_NAME = "ReplayCommitLogsToSqlAction";

  private static final Duration REPLAY_LOCK_LEASE_LENGTH = Duration.standardHours(1);
  private static final java.time.Duration REPLAY_LOCK_ACQUIRE_TIMEOUT =
      java.time.Duration.ofMinutes(6);
  private static final java.time.Duration REPLAY_LOCK_ACQUIRE_DELAY =
      java.time.Duration.ofSeconds(30);

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
    // TODO(weiminyu): ensure migration stage is DATASTORE_PRIMARY or DATASTORE_PRIMARY_READ_ONLY
    Optional<Lock> lock = acquireCommitLogReplayLock();
    if (lock.isPresent()) {
      logger.atInfo().log("Acquired CommitLog Replay lock.");
    } else {
      throw new RuntimeException("Failed to acquire CommitLog Replay lock.");
    }

    try {
      DateTime latestCommitLogTime =
          TransactionManagerFactory.jpaTm().transact(() -> SqlReplayCheckpoint.get());
      Preconditions.checkState(
          latestCommitLogTime.isAfter(mostRecentExport.exportInterval().getEnd()),
          "Cannot recreate Datastore snapshot since target time is in the middle of an export.");
      try (DatabaseSnapshot databaseSnapshot = DatabaseSnapshot.createSnapshot()) {
        // Eagerly release the commitlog replay lock so that replay can resume.
        lock.ifPresent(Lock::releaseSql);
        lock = Optional.empty();

        setupPipeline(pipeline, Optional.of(databaseSnapshot.getSnapshotId()), latestCommitLogTime);
        State state = pipeline.run().waitUntilFinish();
        if (!State.DONE.equals(state)) {
          throw new IllegalStateException("Unexpected pipeline state: " + state);
        }
      }
    } finally {
      lock.ifPresent(Lock::releaseSql);
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
            mostRecentExport.exportInterval().getStart().minus(COMMITLOG_START_TIME_MARGIN),
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

  private static Optional<Lock> acquireCommitLogReplayLock() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    while (stopwatch.elapsed().minus(REPLAY_LOCK_ACQUIRE_TIMEOUT).isNegative()) {
      Optional<Lock> lock = tryAcquireCommitLogReplayLock();
      if (lock.isPresent()) {
        return lock;
      }
      logger.atInfo().log("Failed to acquired CommitLog Replay lock. Will retry...");
      try {
        Thread.sleep(REPLAY_LOCK_ACQUIRE_DELAY.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted.");
      }
    }
    return Optional.empty();
  }

  private static Optional<Lock> tryAcquireCommitLogReplayLock() {
    return Lock.acquireSql(
        COMMITLOG_REPLAY_LOCK_NAME,
        null,
        REPLAY_LOCK_LEASE_LENGTH,
        getLockingRequestStatusChecker(),
        false);
  }

  /**
   * Returns a fake implementation of {@link RequestStatusChecker} that is required for lock
   * acquisition. The default implementation is AppEngine-specific and is unusable on GCE.
   */
  private static RequestStatusChecker getLockingRequestStatusChecker() {
    return new RequestStatusChecker() {
      @Override
      public String getLogId() {
        return "ValidateSqlPipeline";
      }

      @Override
      public boolean isRunning(String requestLogId) {
        return true;
      }
    };
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
