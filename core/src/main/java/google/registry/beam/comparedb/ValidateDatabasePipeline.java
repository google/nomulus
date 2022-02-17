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
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import google.registry.beam.common.RegistryPipelineOptions;
import google.registry.beam.common.RegistryPipelineWorkerInitializer;
import google.registry.beam.comparedb.LatestDatastoreSnapshotFinder.DatastoreSnapshotInfo;
import google.registry.beam.comparedb.ValidateSqlUtils.CompareSqlEntity;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.replay.SqlEntity;
import google.registry.persistence.PersistenceModule.JpaTransactionManagerType;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.util.SystemClock;
import java.io.Serializable;
import java.util.Optional;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.io.TextIO;
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
 * Validates the asynchronous data replication process between Datastore and Cloud SQL.
 *
 * <p>This pipeline is to be launched by {@link google.registry.tools.ValidateDatastoreCommand} or
 * {@link google.registry.tools.ValidateSqlCommand}.
 */
@DeleteAfterMigration
public class ValidateDatabasePipeline {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Specifies the extra CommitLogs to load before the start of a Database export. */
  private static final Duration COMMITLOG_START_TIME_MARGIN = Duration.standardMinutes(10);

  private final ValidateDatabasePipelineOptions options;
  private final LatestDatastoreSnapshotFinder datastoreSnapshotFinder;

  public ValidateDatabasePipeline(
      ValidateDatabasePipelineOptions options,
      LatestDatastoreSnapshotFinder datastoreSnapshotFinder) {
    this.options = options;
    this.datastoreSnapshotFinder = datastoreSnapshotFinder;
  }

  @VisibleForTesting
  void run(Pipeline pipeline) {
    DateTime latestCommitLogTime = DateTime.parse(options.getLatestCommitLogTimestamp());
    DatastoreSnapshotInfo mostRecentExport =
        datastoreSnapshotFinder.getSnapshotInfo(latestCommitLogTime.toInstant());

    logger.atInfo().log(
        "Comparing datastore export at %s and commitlog timestamp %s.",
        mostRecentExport.exportDir(), latestCommitLogTime);

    Optional<String> outputPath =
        Optional.ofNullable(options.getDiffOutputGcsBucket())
            .map(
                bucket ->
                    String.format(
                        "gs://%s/validate_database/%s/diffs.txt",
                        bucket, new SystemClock().nowUtc()));
    outputPath.ifPresent(path -> logger.atInfo().log("Discrepancies will be logged to %s", path));

    setupPipeline(
        pipeline,
        Optional.ofNullable(options.getSqlSnapshotId()),
        mostRecentExport,
        latestCommitLogTime,
        Optional.ofNullable(options.getComparisonStartTimestamp()).map(DateTime::parse),
        outputPath);

    pipeline.run();
  }

  static void setupPipeline(
      Pipeline pipeline,
      Optional<String> sqlSnapshotId,
      DatastoreSnapshotInfo mostRecentExport,
      DateTime latestCommitLogTime,
      Optional<DateTime> compareStartTime,
      Optional<String> diffOutputPath) {
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
            DatastoreSnapshots.ALL_DATASTORE_KINDS,
            compareStartTime);

    PCollectionTuple cloudSqlSnapshot =
        SqlSnapshots.loadCloudSqlSnapshotByType(
            pipeline, SqlSnapshots.ALL_SQL_ENTITIES, sqlSnapshotId, compareStartTime);

    verify(
        datastoreSnapshot.getAll().keySet().equals(cloudSqlSnapshot.getAll().keySet()),
        "Expecting the same set of types in both snapshots.");

    PCollectionList<String> diffLogs = PCollectionList.empty(pipeline);

    for (Class<? extends SqlEntity> clazz : SqlSnapshots.ALL_SQL_ENTITIES) {
      TupleTag<SqlEntity> tag = ValidateSqlUtils.createSqlEntityTupleTag(clazz);
      verify(
          datastoreSnapshot.has(tag), "Missing %s in Datastore snapshot.", clazz.getSimpleName());
      verify(cloudSqlSnapshot.has(tag), "Missing %s in Cloud SQL snapshot.", clazz.getSimpleName());
      diffLogs =
          diffLogs.and(
              PCollectionList.of(datastoreSnapshot.get(tag))
                  .and(cloudSqlSnapshot.get(tag))
                  .apply(
                      "Combine from both snapshots: " + clazz.getSimpleName(),
                      Flatten.pCollections())
                  .apply(
                      "Assign primary key to merged " + clazz.getSimpleName(),
                      WithKeys.of(ValidateDatabasePipeline::getPrimaryKeyString)
                          .withKeyType(strings()))
                  .apply("Group by primary key " + clazz.getSimpleName(), GroupByKey.create())
                  .apply("Compare " + clazz.getSimpleName(), ParDo.of(new CompareSqlEntity())));
    }
    if (diffOutputPath.isPresent()) {
      diffLogs
          .apply("Gather diff logs", Flatten.pCollections())
          .apply(
              "Output diffs",
              TextIO.write()
                  .to(diffOutputPath.get())
                  /**
                   * Output to a single file for ease of use since diffs should be few. If this
                   * assumption turns out not to be false, user should abort the pipeline and
                   * investigate why.
                   */
                  .withoutSharding()
                  .withDelimiter((Strings.repeat("-", 80) + "\n").toCharArray()));
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
    ValidateDatabasePipelineOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(ValidateDatabasePipelineOptions.class);
    RegistryPipelineOptions.validateRegistryPipelineOptions(options);

    // Defensively set important options.
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ);
    options.setJpaTransactionManagerType(JpaTransactionManagerType.BULK_QUERY);

    // Set up JPA in the pipeline harness (the locally executed part of the main() method). Reuse
    // code in RegistryPipelineWorkerInitializer, which only applies to pipeline worker VMs.
    new RegistryPipelineWorkerInitializer().beforeProcessing(options);

    LatestDatastoreSnapshotFinder datastoreSnapshotFinder =
        DaggerLatestDatastoreSnapshotFinder_LatestDatastoreSnapshotFinderFinderComponent.create()
            .datastoreSnapshotInfoFinder();

    new ValidateDatabasePipeline(options, datastoreSnapshotFinder).run(Pipeline.create(options));
  }
}
