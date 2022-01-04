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

package google.registry.model.ofy;

import static google.registry.model.ofy.EntityWritePriorities.getEntityPriority;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryEnvironment;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.replay.DatastoreEntity;
import google.registry.model.replay.ReplaySpecializer;
import google.registry.persistence.VKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Implements simplified datastore to SQL transaction replay.
 *
 * <p>This code is to be removed when the actual replay cron job is implemented.
 */
@DeleteAfterMigration
public class ReplayQueue {

  static ConcurrentLinkedQueue<ImmutableMap<Key<?>, Object>> queue =
      new ConcurrentLinkedQueue<ImmutableMap<Key<?>, Object>>();

  static void addInTests(TransactionInfo info) {
    if (RegistryEnvironment.get() == RegistryEnvironment.UNITTEST) {
      // Transform the entities to be persisted to the set of values as they were actually
      // persisted.
      ImmutableMap.Builder<Key<?>, Object> builder = new ImmutableMap.Builder<Key<?>, Object>();
      for (ImmutableMap.Entry<Key<?>, Object> entry : info.getChanges().entrySet()) {
        if (entry.getValue().equals(TransactionInfo.Delete.SENTINEL)) {
          builder.put(entry.getKey(), entry.getValue());
        } else {
          // The value is an entity object that has not yet been persisted, and thus some of the
          // special transformations that we do (notably the auto-timestamp transformations) have
          // not been applied.  Converting the object to an entity and then back again performs
          // those transformations so that we persist the same values to SQL that we have in
          // Datastore.
          builder.put(entry.getKey(), auditedOfy().toPojo(auditedOfy().toEntity(entry.getValue())));
        }
      }
      queue.add(builder.build());
    }
  }

  /** Replay all transactions, return the set of keys that were replayed. */
  public static ImmutableMap<Key<?>, Object> replay() {
    // We can't use an ImmutableMap.Builder here, we need to be able to overwrite existing values
    // and the builder doesn't support that.
    Map<Key<?>, Object> result = new HashMap<Key<?>, Object>();
    ImmutableMap<Key<?>, Object> changes;
    while ((changes = queue.poll()) != null) {
      saveToJpa(changes);
      result.putAll(changes);
    }

    return ImmutableMap.copyOf(result);
  }

  public static void clear() {
    queue.clear();
  }

  /** Returns the priority of the entity type in the map entry. */
  private static int getPriority(ImmutableMap.Entry<Key<?>, Object> entry) {
    return getEntityPriority(
        entry.getKey().getKind(), entry.getValue().equals(TransactionInfo.Delete.SENTINEL));
  }

  private static int compareByPriority(
      ImmutableMap.Entry<Key<?>, Object> a, ImmutableMap.Entry<Key<?>, Object> b) {
    return getPriority(a) - getPriority(b);
  }

  private static void saveToJpa(ImmutableMap<Key<?>, Object> changes) {
    try (UpdateAutoTimestamp.DisableAutoUpdateResource disabler =
        UpdateAutoTimestamp.disableAutoUpdate()) {
      // Sort the changes into an order that will work for insertion into the database.
      jpaTm()
          .transact(
              () ->
                  changes.entrySet().stream()
                      .sorted(ReplayQueue::compareByPriority)
                      .forEach(
                          entry -> {
                            if (entry.getValue().equals(TransactionInfo.Delete.SENTINEL)) {
                              VKey<?> vkey = VKey.from(entry.getKey());
                              ReplaySpecializer.beforeSqlDelete(vkey);
                              jpaTm().delete(vkey);
                            } else {
                              ((DatastoreEntity) entry.getValue())
                                  .toSqlEntity()
                                  .ifPresent(jpaTm()::put);
                            }
                          }));
    }
  }
}
