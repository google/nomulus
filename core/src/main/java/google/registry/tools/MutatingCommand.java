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

package google.registry.tools;

import static com.google.common.base.CaseFormat.UPPER_CAMEL;
import static com.google.common.base.CaseFormat.UPPER_UNDERSCORE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.emptyToNull;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DatastoreServiceUtils.getNameOrId;
import static google.registry.util.DiffUtils.prettyPrintEntityDeepDiff;
import static java.util.stream.Collectors.joining;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** A {@link ConfirmingCommand} that changes objects in Datastore. */
public abstract class MutatingCommand extends ConfirmingCommand implements CommandWithRemoteApi {

  /**
   * A mutation of a specific entity, represented by an old and a new version of the entity.
   * Storing the old version is necessary to enable checking that the existing entity has not been
   * modified when applying a mutation that was created outside the same transaction.
   */
  private static class EntityChange {

    /** The possible types of mutation that can be performed on an entity. */
    public enum ChangeType {
      CREATE, DELETE, UPDATE;

      /** Return the ChangeType corresponding to the given combination of version existences. */
      public static ChangeType get(boolean hasOldVersion, boolean hasNewVersion) {
        checkArgument(
            hasOldVersion || hasNewVersion,
            "An entity change must have an old version or a new version (or both)");
        return !hasOldVersion ? CREATE : (!hasNewVersion ? DELETE : UPDATE);
      }
    }

    /** The type of mutation being performed on the entity. */
    final ChangeType type;

    /** The old version of the entity, or null if this is a create. */
    final ImmutableObject oldEntity;

    /** The new version of the entity, or null if this is a delete. */
    final ImmutableObject newEntity;

    /** The key that points to the entity being changed. */
    final Key<ImmutableObject> key;

    public EntityChange(ImmutableObject oldEntity, ImmutableObject newEntity) {
      type = ChangeType.get(oldEntity != null, newEntity != null);
      checkArgument(
          type != ChangeType.UPDATE || Key.create(oldEntity).equals(Key.create(newEntity)),
          "Both entity versions in an update must have the same Key.");
      this.oldEntity = oldEntity;
      this.newEntity = newEntity;
      key = Key.create(MoreObjects.firstNonNull(oldEntity, newEntity));
    }

    /** Returns a human-readable ID string for the entity being changed. */
    public String getEntityId() {
      return String.format(
          "%s@%s",
          key.getKind(),
          // NB: try name before id, since name defaults to null, whereas id defaults to 0.
          getNameOrId(key.getRaw()));
    }

    /** Returns a string representation of this entity change. */
    @Override
    public String toString() {
      String changeText;
      if (type == ChangeType.UPDATE) {
        String diffText = prettyPrintEntityDeepDiff(
            oldEntity.toDiffableFieldMap(), newEntity.toDiffableFieldMap());
        changeText = Optional.ofNullable(emptyToNull(diffText)).orElse("[no changes]\n");
      } else {
        changeText = MoreObjects.firstNonNull(oldEntity, newEntity) + "\n";
      }
      return String.format(
          "%s %s\n%s",
          UPPER_UNDERSCORE.to(UPPER_CAMEL, type.toString()), getEntityId(), changeText);
    }
  }

  /** Map from entity keys to EntityChange objects representing changes to those entities. */
  private final LinkedHashMap<Key<ImmutableObject>, EntityChange> changedEntitiesMap =
      new LinkedHashMap<>();

  /** A set of resource keys for which new transactions should be created after. */
  private final Set<Key<ImmutableObject>> transactionBoundaries = new HashSet<>();

  @Nullable
  private Key<ImmutableObject> lastAddedKey;

  /**
   * Initializes the command.
   *
   * <p>Subclasses override this method to populate {@link #changedEntitiesMap} with updated
   * entities. The old entity is the key and the new entity is the value; the key is null for newly
   * created entities and the value is null for deleted entities.
   */
  @Override
  protected abstract void init() throws Exception;

  /**
   * Performs the command and returns a result description.
   *
   * <p>Subclasses can override this method if the command does something besides update entities,
   * such as running a full flow.
   */
  @Override
  protected String execute() throws Exception {
    for (final List<EntityChange> batch : getCollatedEntityChangeBatches()) {
      tm().transact(() -> batch.forEach(this::executeChange));
    }
    return String.format("Updated %d entities.\n", changedEntitiesMap.size());
  }

  private void executeChange(EntityChange change) {
    // Load the key of the entity to mutate and double-check that it hasn't been
    // modified from the version that existed when the change was prepared.
    ImmutableObject existingEntity = ofy().load().key(change.key).now();
    checkState(
        Objects.equals(change.oldEntity, existingEntity),
        "Entity changed since init() was called.\n%s",
        prettyPrintEntityDeepDiff(
            (change.oldEntity == null) ? ImmutableMap.of() : change.oldEntity.toDiffableFieldMap(),
            (existingEntity == null) ? ImmutableMap.of() : existingEntity.toDiffableFieldMap()));
    switch (change.type) {
      case CREATE: // Fall through.
      case UPDATE:
        ofy().save().entity(change.newEntity).now();
        return;
      case DELETE:
        ofy().delete().key(change.key).now();
        return;
    }
    throw new UnsupportedOperationException("Unknown entity change type: " + change.type);
  }

  /**
   * Returns a set of lists of EntityChange actions to commit.  Each list should be executed in
   * order inside a single transaction.
   */
  private ImmutableSet<ImmutableList<EntityChange>> getCollatedEntityChangeBatches() {
    ImmutableSet.Builder<ImmutableList<EntityChange>> batches = new ImmutableSet.Builder<>();
    ArrayList<EntityChange> nextBatch = new ArrayList<>();
    for (EntityChange change : changedEntitiesMap.values()) {
      nextBatch.add(change);
      if (transactionBoundaries.contains(change.key)) {
        batches.add(ImmutableList.copyOf(nextBatch));
        nextBatch.clear();
      }
    }
    if (!nextBatch.isEmpty()) {
      batches.add(ImmutableList.copyOf(nextBatch));
    }
    return batches.build();
  }

  /**
   * Subclasses can call this to stage a mutation to an entity that will be applied by execute().
   * Note that both objects passed must correspond to versions of the same entity with the same key.
   *
   * @param oldEntity the existing version of the entity, or null to create a new entity
   * @param newEntity the new version of the entity to save, or null to delete the entity
   */
  protected void stageEntityChange(
      @Nullable ImmutableObject oldEntity, @Nullable ImmutableObject newEntity) {
    EntityChange change = new EntityChange(oldEntity, newEntity);
    checkArgument(
        !changedEntitiesMap.containsKey(change.key),
        "Cannot apply multiple changes for the same entity: %s",
        change.getEntityId());
    changedEntitiesMap.put(change.key, change);
    lastAddedKey = change.key;
  }

  /**
   * Subclasses can call this to write out all previously requested entity changes since the last
   * transaction flush in a transaction.
   */
  protected void flushTransaction() {
    transactionBoundaries.add(checkNotNull(lastAddedKey));
  }

  /** Returns the changes that have been staged thus far. */
  @Override
  protected String prompt() {
    return changedEntitiesMap.isEmpty()
        ? "No entity changes to apply."
        : changedEntitiesMap.values().stream().map(Object::toString).collect(joining("\n"));
  }
}
