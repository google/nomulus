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

import static google.registry.backup.ExportCommitLogDiffAction.DIFF_FILE_PREFIX;
import static google.registry.model.ofy.EntityWritePriorities.getEntityPriority;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static org.joda.time.Duration.standardHours;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.tools.cloudstorage.GcsFileMetadata;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig;
import google.registry.model.server.Lock;
import google.registry.model.translators.VKeyTranslatorFactory;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.DatastoreOnlyEntity;
import google.registry.schema.replay.NonReplicatedEntity;
import google.registry.schema.replay.SqlEntity;
import google.registry.schema.replay.SqlReplayCheckpoint;
import google.registry.util.RequestStatusChecker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Action that replays commit logs to Cloud SQL to keep it up to date. */
@Action(
    service = Action.Service.BACKEND,
    path = ReplayCommitLogsToSqlAction.PATH,
    method = Action.Method.POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ReplayCommitLogsToSqlAction implements Runnable {

  static final String PATH = "/_dr/task/replayCommitLogsToSql";

  private static final int BLOCK_SIZE =
      1024 * 1024; // Buffer 1mb at a time, for no particular reason.
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Duration LEASE_LENGTH = standardHours(1);

  @Inject GcsService gcsService;
  @Inject Response response;
  @Inject RequestStatusChecker requestStatusChecker;
  @Inject GcsDiffFileLister diffLister;

  @Inject
  ReplayCommitLogsToSqlAction() {}

  @Override
  public void run() {
    if (!RegistryConfig.getCloudSqlReplayCommitLogs()) {
      String message = "ReplayCommitLogsToSqlAction was called but disabled in the config.";
      logger.atWarning().log(message);
      // App Engine will retry on any non-2xx status code, which we don't want in this case.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload(message);
      return;
    }
    Optional<Lock> lock =
        Lock.acquire(
            this.getClass().getSimpleName(), null, LEASE_LENGTH, requestStatusChecker, false);
    if (lock.isEmpty()) {
      String message = "Can't acquire SQL commit log replay lock, aborting.";
      logger.atSevere().log(message);
      // App Engine will retry on any non-2xx status code, which we don't want in this case.
      // Let the next run after the next export happen naturally.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload(message);
      return;
    }
    try {
      replayFiles();
      response.setStatus(HttpServletResponse.SC_OK);
      logger.atInfo().log("ReplayCommitLogsToSqlAction completed successfully.");
    } finally {
      lock.ifPresent(Lock::release);
    }
  }

  private void replayFiles() {
    // Start at the first millisecond we haven't seen yet
    DateTime fromTime = jpaTm().transact(() -> SqlReplayCheckpoint.get().plusMillis(1));
    // If there's an inconsistent file set, this will throw IllegalStateException and the job
    // will try later -- this is likely because an export hasn't finished yet.
    ImmutableList<GcsFileMetadata> commitLogFiles =
        diffLister.listDiffFiles(fromTime, /* current time */ null);
    for (GcsFileMetadata metadata : commitLogFiles) {
      // One transaction per GCS file
      jpaTm().transact(() -> processFile(metadata));
    }
    logger.atInfo().log("Replayed %d commit log files to SQL successfully.", commitLogFiles.size());
  }

  private void processFile(GcsFileMetadata metadata) {
    try (InputStream input =
        Channels.newInputStream(
            gcsService.openPrefetchingReadChannel(metadata.getFilename(), 0, BLOCK_SIZE))) {
      // Load and process the Datastore transactions one at a time
      ImmutableList<ImmutableList<VersionedEntity>> allTransactions =
          CommitLogImports.loadEntitiesByTransaction(input);
      allTransactions.forEach(this::replayTransaction);
      // if we succeeded, set the last-seen time
      DateTime checkpoint =
          DateTime.parse(
              metadata.getFilename().getObjectName().substring(DIFF_FILE_PREFIX.length()));
      SqlReplayCheckpoint.set(checkpoint);
      logger.atInfo().log("Replayed %d transactions from commit log file.", allTransactions.size());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void replayTransaction(ImmutableList<VersionedEntity> transaction) {
    transaction.stream()
        .sorted(ReplayCommitLogsToSqlAction::compareByWeight)
        .forEach(
            versionedEntity ->
                versionedEntity
                    .getEntity()
                    .ifPresentOrElse(
                        this::handleEntityPut, () -> handleEntityDelete(versionedEntity)));
  }

  private void handleEntityPut(Entity entity) {
    Object ofyPojo = ofy().toPojo(entity);
    if (ofyPojo instanceof DatastoreEntity) {
      DatastoreEntity datastoreEntity = (DatastoreEntity) ofyPojo;
      ImmutableList<SqlEntity> convertedEntities = datastoreEntity.toSqlEntities();
      jpaTm().putAll(convertedEntities);
    } else {
      // this should never happen, but we shouldn't fail on it
      logger.atSevere().log(
          "%s does not implement DatastoreEntity, which is necessary for SQL replay.",
          ofyPojo.getClass());
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
    Class<?> entityClass = entityVKey.getKind();
    // Delete the key iff the class represents a JPA entity that is replicated
    if (!NonReplicatedEntity.class.isAssignableFrom(entityClass)
        && !DatastoreOnlyEntity.class.isAssignableFrom(entityClass)
        && entityClass.getAnnotation(javax.persistence.Entity.class) != null) {
      jpaTm().delete(entityVKey);
    }
  }

  private static int compareByWeight(VersionedEntity a, VersionedEntity b) {
    return getEntityPriority(a.key().getKind(), a.getEntity().isEmpty())
        - getEntityPriority(b.key().getKind(), b.getEntity().isEmpty());
  }
}
