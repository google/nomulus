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


import static com.google.appengine.api.datastore.EntityTranslator.convertToPb;
import static com.google.appengine.api.datastore.EntityTranslator.createFromPbBytes;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.google.appengine.api.datastore.KeyFactory;
import com.google.common.annotations.VisibleForTesting;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.replay.DatastoreOnlyEntity;

/** Representation of a saved entity in a {@link CommitLogManifest} (not deletes). */
@Entity
@NotBackedUp(reason = Reason.COMMIT_LOGS)
public class CommitLogMutation extends ImmutableObject implements DatastoreOnlyEntity {

  /** The manifest this belongs to. */
  @Parent
  Key<CommitLogManifest> parent;

  /** Serialized web-safe string representation of saved entity key. */
  @Id
  String entityKey;

  /**
   * Raw entity that was saved during the transaction, serialized as a protocol buffer.
   *
   * <p>This value will be written to a GCS file by an export task.
   */
  byte[] entityProtoBytes;

  public byte[] getEntityProtoBytes() {
    return entityProtoBytes.clone();
  }

  /** Deserializes embedded entity bytes and returns it. */
  public com.google.appengine.api.datastore.Entity getEntity() {
    return createFromPbBytes(entityProtoBytes);
  }

  /**
   * Returns a new mutation entity created from an @Entity ImmutableObject instance.
   *
   * <p>The mutation key is generated deterministically from the {@code entity} key. The object is
   * converted to a raw Datastore Entity, serialized to bytes, and stored within the mutation.
   */
  public static CommitLogMutation create(Key<CommitLogManifest> parent, Object entity) {
    return createFromRaw(parent, auditedOfy().save().toEntity(entity));
  }

  /**
   * Returns a new mutation entity created from a raw Datastore Entity instance.
   *
   * <p>The mutation key is generated deterministically from the {@code entity} key. The Entity
   * itself is serialized to bytes and stored within the returned mutation.
   */
  @VisibleForTesting
  public static CommitLogMutation createFromRaw(
      Key<CommitLogManifest> parent,
      com.google.appengine.api.datastore.Entity rawEntity) {
    CommitLogMutation instance = new CommitLogMutation();
    instance.parent = checkNotNull(parent);
    // TODO(b/207516684): figure out if this should be converted to a vkey string via stringify()
    // Creates a web-safe key string.
    instance.entityKey = KeyFactory.keyToString(rawEntity.getKey());
    instance.entityProtoBytes = convertToPb(rawEntity).toByteArray();
    return instance;
  }

  /** Returns the key of a mutation based on the {@code entityKey} of the entity it stores. */
  public static
      Key<CommitLogMutation> createKey(Key<CommitLogManifest> parent, Key<?> entityKey) {
    // TODO(b/207516684): figure out if the return type needs to be VKey and
    //  if the string used to create a key should remain the same
    return Key.create(parent, CommitLogMutation.class, entityKey.getString());
  }
}
