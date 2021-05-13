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

package google.registry.backup;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterators.peekingIterator;
import static google.registry.backup.BackupUtils.createDeserializingIterator;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.appengine.tools.cloudstorage.GcsFileMetadata;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Result;
import com.googlecode.objectify.util.ResultNow;
import google.registry.config.RegistryEnvironment;
import google.registry.model.ImmutableObject;
import google.registry.model.ofy.CommitLogBucket;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Retrier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Restore Registry 2 commit logs from GCS to Datastore. */
@Action(
    service = Action.Service.TOOLS,
    path = RestoreCommitLogsAction.PATH,
    method = Action.Method.POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class RestoreCommitLogsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final int BLOCK_SIZE = 1024 * 1024;  // Buffer 1mb at a time, for no particular reason.

  public static final String PATH = "/_dr/task/restoreCommitLogs";
  static final String DRY_RUN_PARAM = "dryRun";
  static final String FROM_TIME_PARAM = "fromTime";
  static final String TO_TIME_PARAM = "toTime";

  @Inject GcsService gcsService;
  @Inject @Parameter(DRY_RUN_PARAM) boolean dryRun;
  @Inject @Parameter(FROM_TIME_PARAM) DateTime fromTime;
  @Inject @Parameter(TO_TIME_PARAM) DateTime toTime;
  @Inject DatastoreService datastoreService;
  @Inject GcsDiffFileLister diffLister;
  @Inject Retrier retrier;
  @Inject RestoreCommitLogsAction() {}

  @Override
  public void run() {
    checkArgument(
        RegistryEnvironment.get() == RegistryEnvironment.ALPHA
            || RegistryEnvironment.get() == RegistryEnvironment.CRASH
            || RegistryEnvironment.get() == RegistryEnvironment.UNITTEST,
        "DO NOT RUN ANYWHERE ELSE EXCEPT ALPHA, CRASH OR TESTS.");
    if (dryRun) {
      logger.atInfo().log("Running in dryRun mode");
    }
    List<GcsFileMetadata> diffFiles = diffLister.listDiffFiles(fromTime, toTime);
    if (diffFiles.isEmpty()) {
      logger.atInfo().log("Nothing to restore");
      return;
    }
    Map<Integer, DateTime> bucketTimestamps = new HashMap<>();
    CommitLogCheckpoint lastCheckpoint = null;
    for (GcsFileMetadata metadata : diffFiles) {
      logger.atInfo().log("Restoring: %s", metadata.getFilename().getObjectName());
      try (InputStream input = Channels.newInputStream(
          gcsService.openPrefetchingReadChannel(metadata.getFilename(), 0, BLOCK_SIZE))) {
        PeekingIterator<ImmutableObject> commitLogs =
            peekingIterator(createDeserializingIterator(input));
        lastCheckpoint = (CommitLogCheckpoint) commitLogs.next();
        saveOfy(ImmutableList.of(lastCheckpoint));  // Save the checkpoint itself.
        while (commitLogs.hasNext()) {
          CommitLogManifest manifest = restoreOneTransaction(commitLogs);
          bucketTimestamps.put(manifest.getBucketId(), manifest.getCommitTime());
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    // Restore the CommitLogCheckpointRoot and CommitLogBuckets.
    saveOfy(
        Streams.concat(
                bucketTimestamps
                    .entrySet()
                    .stream()
                    .map(
                        entry ->
                            new CommitLogBucket.Builder()
                                .setBucketNum(entry.getKey())
                                .setLastWrittenTime(entry.getValue())
                                .build()),
                Stream.of(CommitLogCheckpointRoot.create(lastCheckpoint.getCheckpointTime())))
            .collect(toImmutableList()));
    logger.atInfo().log("Restore complete");
  }

  /**
   * Restore the contents of one transaction to Datastore.
   *
   * <p>The objects to delete are listed in the {@link CommitLogManifest}, which will be the first
   * object in the iterable. The objects to save follow, each as a {@link CommitLogMutation}. We
   * restore by deleting the deletes and recreating the saves from their proto form. We also save
   * the commit logs themselves back to Datastore, so that the commit log system itself is
   * transparently restored alongside the data.
   *
   * @return the manifest, for use in restoring the {@link CommitLogBucket}.
   */
  private CommitLogManifest restoreOneTransaction(PeekingIterator<ImmutableObject> commitLogs) {
    final CommitLogManifest manifest = (CommitLogManifest) commitLogs.next();
    Result<?> deleteResult = deleteAsync(manifest.getDeletions());
    List<Entity> entitiesToSave = Lists.newArrayList(auditedOfy().save().toEntity(manifest));
    while (commitLogs.hasNext() && commitLogs.peek() instanceof CommitLogMutation) {
      CommitLogMutation mutation = (CommitLogMutation) commitLogs.next();
      entitiesToSave.add(auditedOfy().save().toEntity(mutation));
      entitiesToSave.add(EntityTranslator.createFromPbBytes(mutation.getEntityProtoBytes()));
    }
    saveRaw(entitiesToSave);
    try {
      deleteResult.now();
    } catch (Exception e) {
      retrier.callWithRetry(
          () -> deleteAsync(manifest.getDeletions()).now(), RuntimeException.class);
    }
    return manifest;
  }

  private void saveRaw(List<Entity> entitiesToSave) {
    if (dryRun) {
      logger.atInfo().log("Would have saved entities: %s", entitiesToSave);
      return;
    }
    retrier.callWithRetry(() -> datastoreService.put(entitiesToSave), RuntimeException.class);
  }

  private void saveOfy(Iterable<? extends ImmutableObject> objectsToSave) {
    if (dryRun) {
      logger.atInfo().log("Would have saved entities: %s", objectsToSave);
      return;
    }
    retrier.callWithRetry(
        () -> auditedOfy().saveWithoutBackup().entities(objectsToSave).now(),
        RuntimeException.class);
  }

  private Result<?> deleteAsync(Set<Key<?>> keysToDelete) {
    if (dryRun) {
      logger.atInfo().log("Would have deleted entities: %s", keysToDelete);
    }
    return dryRun || keysToDelete.isEmpty()
        ? new ResultNow<Void>(null)
        : auditedOfy().deleteWithoutBackup().keys(keysToDelete);
  }

}
