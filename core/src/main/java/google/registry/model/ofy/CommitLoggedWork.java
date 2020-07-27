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

package google.registry.model.ofy;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.filterKeys;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.union;
import static google.registry.model.ofy.CommitLogBucket.loadBucket;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.BackupGroupRoot;
import google.registry.model.ImmutableObject;
import google.registry.util.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Supplier;
import org.joda.time.DateTime;

/** Wrapper for {@link Supplier} that associates a time with each attempt. */
class CommitLoggedWork<R> implements Runnable {

  private final Supplier<R> work;
  private final Clock clock;

  /**
   * Temporary place to store the result of a non-void work.
   *
   * <p>We don't want to return the result directly because we are going to try to recover from a
   * {@link com.google.appengine.api.datastore.DatastoreTimeoutException} deep inside Objectify when
   * it tries to commit the transaction. When an exception is thrown the return value would be lost,
   * but sometimes we will be able to determine that we actually succeeded despite the timeout, and
   * we'll want to get the result.
   */
  private R result;

  /**
   * Temporary place to store the key of the commit log manifest.
   *
   * <p>We can use this to determine whether a transaction that failed with a
   * {@link com.google.appengine.api.datastore.DatastoreTimeoutException} actually succeeded. If
   * the manifest exists, and if the contents of the commit log are what we expected to have saved,
   * then the transaction committed. If the manifest does not exist, then the transaction failed and
   * is retryable.
   */
  protected CommitLogManifest manifest;

  /**
   * Temporary place to store the mutations belonging to the commit log manifest.
   *
   * <p>These are used along with the manifest to determine whether a transaction succeeded.
   */
  protected ImmutableSet<ImmutableObject> mutations = ImmutableSet.of();

  /** Lifecycle marker to track whether {@link #run} has been called. */
  private boolean runCalled;

  CommitLoggedWork(Supplier<R> work, Clock clock) {
    this.work = work;
    this.clock = clock;
  }

  protected TransactionInfo createNewTransactionInfo() {
    return new TransactionInfo(clock.nowUtc());
  }

  boolean hasRun() {
    return runCalled;
  }

  R getResult() {
    checkState(runCalled, "Cannot call getResult() before run()");
    return result;
  }

  CommitLogManifest getManifest() {
    checkState(runCalled, "Cannot call getManifest() before run()");
    return manifest;
  }

  ImmutableSet<ImmutableObject> getMutations() {
    checkState(runCalled, "Cannot call getMutations() before run()");
    return mutations;
  }

  @Override
  public void run() {
    // The previous time will generally be null, except when using transactNew.
    TransactionInfo previous = Ofy.TRANSACTION_INFO.get();
    // Set the time to be used for "now" within the transaction.
    try {
      Ofy.TRANSACTION_INFO.set(createNewTransactionInfo());
      result = work.get();
      saveCommitLog(Ofy.TRANSACTION_INFO.get());
    } finally {
      Ofy.TRANSACTION_INFO.set(previous);
    }
    runCalled = true;
  }

  /** Records all mutations enrolled by this transaction to a {@link CommitLogManifest} entry. */
  private void saveCommitLog(TransactionInfo info) {
    ImmutableSet<Key<?>> touchedKeys = info.getTouchedKeys();
    if (touchedKeys.isEmpty()) {
      return;
    }
    CommitLogBucket bucket = loadBucket(info.bucketKey);
    // Enforce unique monotonic property on CommitLogBucket.getLastWrittenTime().
    if (isBeforeOrAt(info.transactionTime, bucket.getLastWrittenTime())) {
      throw new TimestampInversionException(info.transactionTime, bucket.getLastWrittenTime());
    }
    // The keys read by Objectify during this transaction. This won't include the keys of
    // asynchronous save and delete operations that haven't been reaped, but that's ok because we
    // already logged all of those keys in {@link TransactionInfo} and now just need to figure out
    // what was loaded.
    ImmutableSet<Key<?>> keysInSessionCache = ofy().getSessionKeys();
    Map<Key<BackupGroupRoot>, BackupGroupRoot> rootsForTouchedKeys =
        getBackupGroupRoots(touchedKeys);
    Map<Key<BackupGroupRoot>, BackupGroupRoot> rootsForUntouchedKeys =
        getBackupGroupRoots(difference(keysInSessionCache, touchedKeys));
    // Check the update timestamps of all keys in the transaction, whether touched or merely read.
    checkBackupGroupRootTimestamps(
        info.transactionTime,
        union(rootsForUntouchedKeys.entrySet(), rootsForTouchedKeys.entrySet()));
    // Find any BGRs that have children which were touched but were not themselves touched.
    Set<BackupGroupRoot> untouchedRootsWithTouchedChildren =
        ImmutableSet.copyOf(filterKeys(rootsForTouchedKeys, not(in(touchedKeys))).values());
    manifest = CommitLogManifest.create(info.bucketKey, info.transactionTime, info.getDeletes());
    final Key<CommitLogManifest> manifestKey = Key.create(manifest);
    mutations =
        union(info.getSaves(), untouchedRootsWithTouchedChildren)
            .stream()
            .map(entity -> (ImmutableObject) CommitLogMutation.create(manifestKey, entity))
            .collect(toImmutableSet());
    ofy().saveWithoutBackup()
      .entities(new ImmutableSet.Builder<>()
          .add(manifest)
          .add(bucket.asBuilder().setLastWrittenTime(info.transactionTime).build())
          .addAll(mutations)
          .addAll(untouchedRootsWithTouchedChildren)
          .build())
      .now();
  }

  /** Check that the timestamp of each BackupGroupRoot is in the past. */
  private void checkBackupGroupRootTimestamps(
      DateTime transactionTime, Set<Entry<Key<BackupGroupRoot>, BackupGroupRoot>> bgrEntries) {
    ImmutableMap.Builder<Key<BackupGroupRoot>, DateTime> builder = new ImmutableMap.Builder<>();
    for (Entry<Key<BackupGroupRoot>, BackupGroupRoot> entry : bgrEntries) {
      DateTime updateTime = entry.getValue().getUpdateTimestamp().getTimestamp();
      if (!updateTime.isBefore(transactionTime)) {
        builder.put(entry.getKey(), updateTime);
      }
    }
    ImmutableMap<Key<BackupGroupRoot>, DateTime> problematicRoots = builder.build();
    if (!problematicRoots.isEmpty()) {
      throw new TimestampInversionException(transactionTime, problematicRoots);
    }
  }

  /** Find the set of {@link BackupGroupRoot} ancestors of the given keys. */
  private Map<Key<BackupGroupRoot>, BackupGroupRoot> getBackupGroupRoots(Iterable<Key<?>> keys) {
    Set<Key<BackupGroupRoot>> rootKeys = new HashSet<>();
    for (Key<?> key : keys) {
      while (key != null
          && !BackupGroupRoot.class
              .isAssignableFrom(ofy().factory().getMetadata(key).getEntityClass())) {
        key = key.getParent();
      }
      if (key != null) {
        @SuppressWarnings("unchecked")
        Key<BackupGroupRoot> rootKey = (Key<BackupGroupRoot>) key;
        rootKeys.add(rootKey);
      }
    }
    return ImmutableMap.copyOf(ofy().load().keys(rootKeys));
  }
}
