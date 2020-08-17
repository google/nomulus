// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.TransactionManager;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Datastore implementation of {@link TransactionManager}. */
public class DatastoreTransactionManager implements TransactionManager {

  private Ofy injectedOfy;

  /** Constructs an instance. */
  public DatastoreTransactionManager(Ofy injectedOfy) {
    this.injectedOfy = injectedOfy;
  }

  private Ofy getOfy() {
    return injectedOfy == null ? ofy() : injectedOfy;
  }

  @Override
  public boolean inTransaction() {
    return getOfy().inTransaction();
  }

  @Override
  public void assertInTransaction() {
    getOfy().assertInTransaction();
  }

  @Override
  public <T> T transact(Supplier<T> work) {
    return getOfy().transact(work);
  }

  @Override
  public void transact(Runnable work) {
    getOfy().transact(work);
  }

  @Override
  public <T> T transactNew(Supplier<T> work) {
    return getOfy().transactNew(work);
  }

  @Override
  public void transactNew(Runnable work) {
    getOfy().transactNew(work);
  }

  @Override
  public <R> R transactNewReadOnly(Supplier<R> work) {
    return getOfy().transactNewReadOnly(work);
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    getOfy().transactNewReadOnly(work);
  }

  @Override
  public <R> R doTransactionless(Supplier<R> work) {
    return getOfy().doTransactionless(work);
  }

  @Override
  public DateTime getTransactionTime() {
    return getOfy().getTransactionTime();
  }

  @Override
  public void saveNew(Object entity) {
    saveEntity(entity);
  }

  @Override
  public void saveAllNew(ImmutableCollection<?> entities) {
    getOfy().save().entities(entities);
  }

  @Override
  public void saveNewOrUpdate(Object entity) {
    saveEntity(entity);
  }

  @Override
  public void saveNewOrUpdateAll(ImmutableCollection<?> entities) {
    getOfy().save().entities(entities);
  }

  @Override
  public void update(Object entity) {
    saveEntity(entity);
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    getOfy().save().entities(entities);
  }

  @Override
  public boolean checkExists(Object entity) {
    return getOfy().load().key(Key.create(entity)).now() != null;
  }

  @Override
  public <T> boolean checkExists(VKey<T> key) {
    return loadNullable(key) != null;
  }

  // TODO: add tests for these methods.  They currently have some degree of test coverage because
  // they are used when retrieving the nameservers which require these, as they are now loaded by
  // VKey instead of by ofy Key.  But ideally, there should be one set of TransactionManager
  // interface tests that are applied to both the datastore and SQL implementations.
  @Override
  public <T> Optional<T> maybeLoad(VKey<T> key) {
    return Optional.ofNullable(loadNullable(key));
  }

  @Override
  public <T> T load(VKey<T> key) {
    T result = loadNullable(key);
    if (result == null) {
      throw new NoSuchElementException(key.toString());
    }
    return result;
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> load(Iterable<? extends VKey<? extends T>> keys) {
    // Keep track of the Key -> VKey mapping so we can translate them back.
    ImmutableMap<Key<T>, VKey<? extends T>> keyMap =
        StreamSupport.stream(keys.spliterator(), false)
            .distinct()
            .collect(toImmutableMap(key -> (Key<T>) key.getOfyKey(), Functions.identity()));

    return getOfy().load().keys(keyMap.keySet()).entrySet().stream()
        .collect(
            toImmutableMap(
                entry -> keyMap.get(entry.getKey()),
                entry -> toChildHistoryEntryIfPossible(entry.getValue())));
  }

  @Override
  public <T> ImmutableList<T> loadAll(Class<T> clazz) {
    // We can do a ofy().load().type(clazz), but this doesn't work in a transaction.
    throw new UnsupportedOperationException("Not available in the Datastore transaction manager");
  }

  @Override
  public void delete(VKey<?> key) {
    getOfy().delete().key(key.getOfyKey()).now();
  }

  @Override
  public void delete(Iterable<? extends VKey<?>> vKeys) {
    // We have to create a list to work around the wildcard capture issue here.
    // See https://docs.oracle.com/javase/tutorial/java/generics/capture.html
    ImmutableList<Key<?>> list =
        StreamSupport.stream(vKeys.spliterator(), false)
            .map(VKey::getOfyKey)
            .collect(toImmutableList());
    getOfy().delete().keys(list).now();
  }

  /**
   * The following three methods exist due to the migration to Cloud SQL.
   *
   * <p>In Cloud SQL, {@link HistoryEntry} objects are represented instead as {@link DomainHistory},
   * {@link ContactHistory}, and {@link HostHistory} objects. During the migration, we do not wish
   * to change the Datastore schema so all of these objects are stored in Datastore as HistoryEntry
   * objects. They are converted to/from the appropriate classes upon retrieval, and converted to
   * HistoryEntry on save. See go/r3.0-history-objects for more details.
   */
  private void saveEntity(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    if (entity instanceof HistoryEntry) {
      entity = ((HistoryEntry) entity).asHistoryEntry();
    }
    getOfy().save().entity(entity);
  }

  @SuppressWarnings("unchecked")
  private <T> T toChildHistoryEntryIfPossible(@Nullable T obj) {
    // NB: The Key of the object in question may not necessarily be the resulting class that we
    // wish to have. Because all *History classes are @EntitySubclasses, their Keys will have type
    // HistoryEntry -- even if you create them based off the *History class.
    if (obj != null && HistoryEntry.class.isAssignableFrom(obj.getClass())) {
      return (T) ((HistoryEntry) obj).toChildHistoryEntity();
    }
    return obj;
  }

  @Nullable
  private <T> T loadNullable(VKey<T> key) {
    return toChildHistoryEntryIfPossible(getOfy().load().key(key.getOfyKey()).now());
  }
}
