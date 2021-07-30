// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.backup;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.backup.ExportCommitLogDiffAction.DIFF_FILE_PREFIX;
import static google.registry.backup.RestoreCommitLogsAction.DRY_RUN_PARAM;
import static google.registry.model.ofy.EntityWritePriorities.getEntityPriority;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.joda.time.Duration.standardHours;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.cloud.storage.BlobInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.DatabaseMigrationStateSchedule;
import google.registry.model.common.DatabaseMigrationStateSchedule.MigrationState;
import google.registry.model.common.DatabaseMigrationStateSchedule.ReplayDirection;
import google.registry.model.server.Lock;
import google.registry.model.translators.VKeyTranslatorFactory;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.DatastoreOnlyEntity;
import google.registry.schema.replay.NonReplicatedEntity;
import google.registry.schema.replay.ReplaySpecializer;
import google.registry.schema.replay.SqlReplayCheckpoint;
import google.registry.util.Clock;
import google.registry.util.RequestStatusChecker;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Action that replays commit logs to Cloud SQL to keep it up to date. */
@Action(
    service = Action.Service.BACKEND,
    path = ReplayCommitLogsToSqlAction.PATH,
    method = Method.POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ReplayCommitLogsToSqlAction implements Runnable {

  static final String PATH = "/_dr/task/replayCommitLogsToSql";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Duration LEASE_LENGTH = standardHours(1);
  // Stop / pause where we are if we've been replaying for more than five minutes to avoid GAE
  // request timeouts
  private static final Duration REPLAY_TIMEOUT_DURATION = Duration.standardMinutes(5);

  @Inject GcsUtils gcsUtils;
  @Inject Response response;
  @Inject RequestStatusChecker requestStatusChecker;
  @Inject GcsDiffFileLister diffLister;
  @Inject Clock clock;

  @Inject
  @Config("commitLogGcsBucket")
  String gcsBucket;

  /** If true, will exit after logging the commit log files that would otherwise be replayed. */
  @Inject
  @Parameter(DRY_RUN_PARAM)
  boolean dryRun;

  @Inject
  ReplayCommitLogsToSqlAction() {}

  @Override
  public void run() {
    MigrationState state = DatabaseMigrationStateSchedule.getValueAtTime(clock.nowUtc());
    if (!state.getReplayDirection().equals(ReplayDirection.DATASTORE_TO_SQL)) {
      String message =
          String.format(
              "Skipping ReplayCommitLogsToSqlAction because we are in migration phase %s.", state);
      logger.atInfo().log(message);
      // App Engine will retry on any non-2xx status code, which we don't want in this case.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload(message);
      return;
    }
    Optional<Lock> lock =
        Lock.acquire(
            this.getClass().getSimpleName(), null, LEASE_LENGTH, requestStatusChecker, false);
    if (!lock.isPresent()) {
      String message = "Can't acquire SQL commit log replay lock, aborting.";
      logger.atSevere().log(message);
      // App Engine will retry on any non-2xx status code, which we don't want in this case.
      // Let the next run after the next export happen naturally.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload(message);
      return;
    }
    try {
      logger.atInfo().log("Beginning replay of commit logs.");
      String resultMessage;
      if (dryRun) {
        resultMessage = executeDryRun();
      } else {
        resultMessage = replayFiles();
      }
      response.setStatus(SC_OK);
      response.setPayload(resultMessage);
      logger.atInfo().log(resultMessage);
    } catch (Throwable t) {
      String message = "Errored out replaying files.";
      logger.atSevere().withCause(t).log(message);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response.setPayload(message);
    } finally {
      lock.ifPresent(Lock::release);
    }
  }

  private String executeDryRun() {
    // Start at the first millisecond we haven't seen yet
    DateTime searchStartTime = jpaTm().transact(() -> SqlReplayCheckpoint.get().plusMillis(1));
    // Search through the end of the hour
    DateTime searchEndTime =
        searchStartTime.withMinuteOfHour(59).withSecondOfMinute(59).withMillisOfSecond(999);
    ImmutableList<String> fileBatch =
        diffLister.listDiffFiles(gcsBucket, searchStartTime, searchEndTime).stream()
            .map(BlobInfo::getName)
            .collect(toImmutableList());
    return String.format(
        "Running in dry-run mode, the first set of commit log files processed would be from "
            + "searching from %s to %s and would contain %d file(s). They are (limit 10): \n%s",
        searchStartTime,
        searchEndTime,
        fileBatch.size(),
        fileBatch.stream().limit(10).collect(toImmutableList()));
  }

  private String replayFiles() {
    DateTime replayTimeoutTime = clock.nowUtc().plus(REPLAY_TIMEOUT_DURATION);
    DateTime searchStartTime = jpaTm().transact(() -> SqlReplayCheckpoint.get().plusMillis(1));
    int filesProcessed = 0;
    // Starting from one millisecond after the last file we processed, search for and import files
    // one hour at a time until we catch up to the current time or we hit the replay timeout (in
    // which case the next run will pick up from where we leave off).
    //
    // We use hour-long batches because GCS supports filename prefix-based searches.
    while (true) {
      if (isAtOrAfter(clock.nowUtc(), replayTimeoutTime)) {
        return String.format(
            "Reached max execution time after replaying %d file(s).", filesProcessed);
      }
      if (isBeforeOrAt(clock.nowUtc(), searchStartTime)) {
        return String.format(
            "Caught up to current time after replaying %d file(s).", filesProcessed);
      }
      // Search through the end of the hour
      DateTime searchEndTime =
          searchStartTime.withMinuteOfHour(59).withSecondOfMinute(59).withMillisOfSecond(999);
      ImmutableList<BlobInfo> fileBatch =
          diffLister.listDiffFiles(gcsBucket, searchStartTime, searchEndTime);
      if (fileBatch.isEmpty()) {
        logger.atInfo().log(
            "No remaining files found in hour %s, continuing search in the next hour.",
            searchStartTime.toString("yyyy-MM-dd HH"));
      }
      for (BlobInfo file : fileBatch) {
        jpaTm().transact(() -> processFile(file));
        filesProcessed++;
        if (clock.nowUtc().isAfter(replayTimeoutTime)) {
          return String.format(
              "Reached max execution time after replaying %d file(s).", filesProcessed);
        }
      }
      searchStartTime = searchEndTime.plusMillis(1);
    }
  }

  private void processFile(BlobInfo metadata) {
    try (InputStream input = gcsUtils.openInputStream(metadata.getBlobId())) {
      // Load and process the Datastore transactions one at a time
      ImmutableList<ImmutableList<VersionedEntity>> allTransactions =
          CommitLogImports.loadEntitiesByTransaction(input);
      allTransactions.forEach(this::replayTransaction);
      // if we succeeded, set the last-seen time
      DateTime checkpoint = DateTime.parse(metadata.getName().substring(DIFF_FILE_PREFIX.length()));
      SqlReplayCheckpoint.set(checkpoint);
      logger.atInfo().log(
          "Replayed %d transactions from commit log file %s with size %d B.",
          allTransactions.size(), metadata.getName(), metadata.getSize());
    } catch (IOException e) {
      throw new RuntimeException(
          "Errored out while replaying commit log file " + metadata.getName(), e);
    }
  }

  private void replayTransaction(ImmutableList<VersionedEntity> transaction) {
    transaction.stream()
        .sorted(ReplayCommitLogsToSqlAction::compareByWeight)
        .forEach(
            versionedEntity -> {
              if (versionedEntity.getEntity().isPresent()) {
                handleEntityPut(versionedEntity.getEntity().get());
              } else {
                handleEntityDelete(versionedEntity);
              }
            });
  }

  private void handleEntityPut(Entity entity) {
    Object ofyPojo = auditedOfy().toPojo(entity);
    try {
      if (ofyPojo instanceof DatastoreEntity) {
        DatastoreEntity datastoreEntity = (DatastoreEntity) ofyPojo;
        datastoreEntity
            .toSqlEntity()
            .ifPresent(
                sqlEntity -> {
                  sqlEntity.beforeSqlSaveOnReplay();
                  jpaTm().put(sqlEntity);
                });
      } else {
        // this should never happen, but we shouldn't fail on it
        logger.atSevere().log(
            "%s does not implement DatastoreEntity, which is necessary for SQL replay.",
            ofyPojo.getClass());
      }
    } catch (Throwable t) {
      logger.atSevere().log("Error when replaying object %s", ofyPojo);
      throw t;
    }
  }

  private void handleEntityDelete(VersionedEntity entityToDelete) {
    Key key = entityToDelete.key();
    VKey<?> entityVKey;
    try {
      entityVKey = VKeyTranslatorFactory.createVKey(key);
    } catch (RuntimeException e) {
      // This means that the key wasn't convertible to VKey through the standard methods or via
      // a createVKey method. This means that the object isn't persisted in SQL so we ignore it.
      logger.atInfo().log(
          "Skipping SQL delete for kind %s since it is not convertible.", key.getKind());
      return;
    }
    try {
      Class<?> entityClass = entityVKey.getKind();
      // Delete the key iff the class represents a JPA entity that is replicated
      if (!NonReplicatedEntity.class.isAssignableFrom(entityClass)
          && !DatastoreOnlyEntity.class.isAssignableFrom(entityClass)
          && entityClass.getAnnotation(javax.persistence.Entity.class) != null) {
        ReplaySpecializer.beforeSqlDelete(entityVKey);
        jpaTm().delete(entityVKey);
      }
    } catch (Throwable t) {
      logger.atSevere().log("Error when deleting key %s", entityVKey);
      throw t;
    }
  }

  private static int compareByWeight(VersionedEntity a, VersionedEntity b) {
    return getEntityPriority(a.key().getKind(), !a.getEntity().isPresent())
        - getEntityPriority(b.key().getKind(), !b.getEntity().isPresent());
  }
}
