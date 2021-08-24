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

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.replay.DatastoreEntity;
import google.registry.model.replay.SqlEntity;
import google.registry.persistence.VKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * A SQL transaction that can be serialized and stored in its own table.
 *
 * <p>Transaction is used to store transactions committed to Cloud SQL in a Transaction table during
 * the second phase of our migration, during which time we will be asynchronously replaying Cloud
 * SQL transactions to datastore.
 *
 * <p>TODO(mmuller): Use these from {@link TransactionManager} to store the contents of an SQL
 * transaction for asynchronous propagation to datastore. Implement a cron endpoint that reads them
 * from the Transaction table and calls writeToDatastore().
 */
public class Transaction extends ImmutableObject implements Buildable {

  // Version id for persisted objects.  Use the creation date for the value, as it's reasonably
  // unique and inherently informative.
  private static final int VERSION_ID = 20200604;

  // Keep a per-thread flag to keep track of whether we're serializing an entity for a transaction.
  // This is used by internal translators to avoid doing things that are dependent on being in a
  // datastore transaction and alter the persisted representation of the entity.
  private static ThreadLocal<Boolean> inSerializationMode = ThreadLocal.withInitial(() -> false);

  private transient ImmutableList<Mutation> mutations;

  @VisibleForTesting
  public ImmutableList<Mutation> getMutations() {
    return mutations;
  }

  /** Write the entire transaction to the datastore in a datastore transaction. */
  public void writeToDatastore() {
    ofyTm()
        .transact(
            () -> {
              for (Mutation mutation : mutations) {
                mutation.writeToDatastore();
              }
            });
  }

  /** Serialize a transaction to a byte array. */
  public byte[] serialize() {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectOutputStream out = new ObjectOutputStream(baos);

      // Write the transaction version id.  This serves as both a version id and a "magic number" to
      // protect us against trying to deserialize some random byte array.
      out.writeInt(VERSION_ID);

      // Write all of the mutations, preceded by their count.
      out.writeInt(mutations.size());
      try {
        inSerializationMode.set(true);
        for (Mutation mutation : mutations) {
          mutation.serializeTo(out);
        }
      } finally {
        inSerializationMode.set(false);
      }

      out.close();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalArgumentException();
    }
  }

  public static Transaction deserialize(byte[] serializedTransaction) throws IOException {
    ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(serializedTransaction));

    // Verify that the data is what we expect.
    int version = in.readInt();
    checkArgument(
        version == VERSION_ID, "Invalid version id.  Expected %s but got %s", VERSION_ID, version);

    Transaction.Builder builder = new Transaction.Builder();
    int mutationCount = in.readInt();
    for (int i = 0; i < mutationCount; ++i) {
      try {
        builder.add(Mutation.deserializeFrom(in));
      } catch (EOFException e) {
        throw new RuntimeException("Serialized transaction terminated prematurely", e);
      }
    }
    if (in.read() != -1) {
      throw new RuntimeException("Unread data at the end of a serialized transaction.");
    }
    return builder.build();
  }

  /** Returns true if the transaction contains no mutations. */
  public boolean isEmpty() {
    return mutations.isEmpty();
  }

  /**
   * Returns true if we are serializing a transaction in the current thread.
   *
   * <p>This should be checked by any Ofy translators prior to making any changes to an entity's
   * state representation based on the assumption that we are currently persisting the entity to
   * datastore.
   */
  public static boolean inSerializationMode() {
    return inSerializationMode.get();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public final TransactionEntity toEntity() {
    return new TransactionEntity(serialize());
  }

  public static class Builder extends GenericBuilder<Transaction, Builder> {

    ImmutableList.Builder<Mutation> listBuilder = new ImmutableList.Builder<>();

    Builder() {}

    protected Builder(Transaction instance) {
      super(instance);
    }

    public Builder addUpdate(Object entity) {
      checkNotNull(entity);
      listBuilder.add(new Update(entity));
      return thisCastToDerived();
    }

    public Builder addDelete(VKey<?> key) {
      checkNotNull(key);
      listBuilder.add(new Delete(key));
      return thisCastToDerived();
    }

    /** Adds a mutation (mainly intended for serialization) */
    Builder add(Mutation mutation) {
      checkNotNull(mutation);
      listBuilder.add(mutation);
      return thisCastToDerived();
    }

    @Override
    public Transaction build() {
      getInstance().mutations = listBuilder.build();
      return super.build();
    }
  }

  /** Base class for database record mutations. */
  public abstract static class Mutation {

    enum Type {
      UPDATE,
      DELETE
    }

    /** Write the changes in the mutation to the datastore. */
    public abstract void writeToDatastore();

    /** Serialize the mutation to the output stream. */
    public abstract void serializeTo(ObjectOutputStream out) throws IOException;

    /** Deserialize a mutation from the input stream. */
    public static Mutation deserializeFrom(ObjectInputStream in) throws IOException {
      try {
        Type type = (Type) in.readObject();
        switch (type) {
          case UPDATE:
            return Update.deserializeFrom(in);
          case DELETE:
            return Delete.deserializeFrom(in);
          default:
            throw new IllegalArgumentException("Unknown enum value: " + type);
        }
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(e);
      }
    }
  }

  /**
   * Record update.
   *
   * <p>Note that we don't have to distinguish between add and update, since this is for replay into
   * the datastore which makes no such distinction.
   *
   * <p>Update serializes its entity using Objectify serialization.
   */
  public static class Update extends Mutation {
    private Object entity;

    Update(Object entity) {
      this.entity =
          (entity instanceof SqlEntity) ? ((SqlEntity) entity).toDatastoreEntity().get() : entity;
    }

    @Override
    public void writeToDatastore() {
      // this should always be the case, but check just in case
      if (entity instanceof DatastoreEntity) {
        ((DatastoreEntity) entity).beforeDatastoreSaveOnReplay();
      }
      ofyTm().put(entity);
    }

    @Override
    public void serializeTo(ObjectOutputStream out) throws IOException {
      out.writeObject(Type.UPDATE);
      Entity realEntity = auditedOfy().toEntity(entity);
      EntityProto proto = EntityTranslator.convertToPb(realEntity);
      out.write(VERSION_ID);
      proto.writeDelimitedTo(out);
    }

    @VisibleForTesting
    public Object getEntity() {
      return entity;
    }

    public static Update deserializeFrom(ObjectInputStream in) throws IOException {
      EntityProto proto = new EntityProto();
      proto.parseDelimitedFrom(in);
      return new Update(auditedOfy().toPojo(EntityTranslator.createFromPb(proto)));
    }
  }

  /**
   * Record deletion.
   *
   * <p>Delete serializes its VKey using Java native serialization.
   */
  public static class Delete extends Mutation {
    private final VKey<?> key;

    Delete(VKey<?> key) {
      this.key = key;
    }

    @Override
    public void writeToDatastore() {
      ofyTm().delete(key);
    }

    @Override
    public void serializeTo(ObjectOutputStream out) throws IOException {
      out.writeObject(Type.DELETE);

      // Java object serialization works for this.
      out.writeObject(key);
    }

    @VisibleForTesting
    public VKey<?> getKey() {
      return key;
    }

    public static Delete deserializeFrom(ObjectInputStream in) throws IOException {
      try {
        return new Delete((VKey<?>) in.readObject());
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(e);
      }
    }
  }
}
