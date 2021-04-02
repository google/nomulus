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

package google.registry.persistence;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.googlecode.objectify.Key;
import google.registry.model.BackupGroupRoot;
import google.registry.model.ImmutableObject;
import google.registry.model.translators.VKeyTranslatorFactory;
import java.io.Serializable;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * VKey is an abstraction that encapsulates the key concept.
 *
 * <p>A VKey instance must contain both the JPA primary key for the referenced entity class and the
 * objectify key for the object.
 */
public class VKey<T> extends ImmutableObject implements Serializable {

  private static final long serialVersionUID = -5291472863840231240L;

  // The SQL key for the referenced entity.
  Object sqlKey;

  // The objectify key for the referenced entity.
  Key<T> ofyKey;

  Class<? extends T> kind;

  VKey() {}

  VKey(Class<? extends T> kind, Key<T> ofyKey, Object sqlKey) {
    this.kind = kind;
    this.ofyKey = ofyKey;
    this.sqlKey = sqlKey;
  }

  /**
   * Creates a {@link VKey} which only contains the sql primary key.
   *
   * <p>Deprecated. Create symmetric keys with create() instead.
   */
  public static <T> VKey<T> createSql(Class<T> kind, Object sqlKey) {
    checkArgumentNotNull(kind, "kind must not be null");
    checkArgumentNotNull(sqlKey, "sqlKey must not be null");
    return new VKey<T>(kind, null, sqlKey);
  }

  /** Creates a {@link VKey} which only contains the ofy primary key. */
  public static <T> VKey<T> createOfy(Class<? extends T> kind, Key<T> ofyKey) {
    checkArgumentNotNull(kind, "kind must not be null");
    checkArgumentNotNull(ofyKey, "ofyKey must not be null");
    return new VKey<T>(kind, ofyKey, null);
  }

  /** Creates a {@link VKey} which only contains both sql and ofy primary key. */
  public static <T> VKey<T> create(Class<T> kind, Object sqlKey, Key<T> ofyKey) {
    checkArgumentNotNull(kind, "kind must not be null");
    checkArgumentNotNull(sqlKey, "sqlKey must not be null");
    checkArgumentNotNull(ofyKey, "ofyKey must not be null");
    return new VKey<T>(kind, ofyKey, sqlKey);
  }

  /**
   * Creates a symmetric {@link VKey} in which both sql and ofy keys are {@code id}.
   *
   * <p>IMPORTANT USAGE NOTE: Datastore entities that are not roots of entity groups (i.e. those
   * that do not have a null parent in their Objectify keys) require the full entity group
   * inheritance chain to be specified and thus cannot use this create method. You need to use
   * {@link #create(Class, Object, Key)} instead and pass in the full, valid parent field in the
   * Datastore key.
   */
  public static <T> VKey<T> create(Class<T> kind, long id) {
    checkArgument(
        BackupGroupRoot.class.isAssignableFrom(kind),
        "The kind %s is not a BackupGroupRoot and thus needs its entire entity group chain"
            + " specified in a parent",
        kind.getCanonicalName());
    return new VKey<T>(kind, Key.create(kind, id), id);
  }

  /**
   * Creates a symmetric {@link VKey} in which both sql and ofy keys are {@code name}.
   *
   * <p>IMPORTANT USAGE NOTE: Datastore entities that are not roots of entity groups (i.e. those
   * that do not have a null parent in their Objectify keys) require the full entity group
   * inheritance chain to be specified and thus cannot use this create method. You need to use
   * {@link #create(Class, Object, Key)} instead and pass in the full, valid parent field in the
   * Datastore key.
   */
  public static <T> VKey<T> create(Class<T> kind, String name) {
    checkArgument(
        BackupGroupRoot.class.isAssignableFrom(kind),
        "The kind %s is not a BackupGroupRoot and thus needs its entire entity group chain"
            + " specified in a parent",
        kind.getCanonicalName());
    return new VKey<T>(kind, Key.create(kind, name), name);
  }

  /**
   * Returns a clone with an ofy key restored from {@code ancestors}.
   *
   * <p>The arguments should generally consist of pairs of Class and value, where the Class is the
   * kind of the ancestor key and the value is either a String or a Long.
   *
   * <p>For example, to restore the objectify key for
   * DomainBase("COM-1234")/HistoryEntry(123)/PollEvent(567), one might use:
   *
   * <pre>{@code
   * pollEvent.restoreOfy(DomainBase.class, "COM-1234", HistoryEntry.class, 567)
   * }</pre>
   *
   * <p>The final key id or name is obtained from the SQL key. It is assumed that this value must be
   * either a long integer or a {@code String} and that this proper identifier for the objectify
   * key.
   *
   * <p>As a special case, an objectify Key may be used as the first ancestor instead of a Class,
   * value pair.
   */
  public VKey<T> restoreOfy(Object... ancestors) {
    Class lastClass = null;
    Key<?> lastKey = null;
    for (Object ancestor : ancestors) {
      if (ancestor instanceof Class) {
        if (lastClass != null) {
          throw new IllegalArgumentException(ancestor + " used as a key value.");
        }
        lastClass = (Class) ancestor;
        continue;
      } else if (ancestor instanceof Key) {
        if (lastKey != null) {
          throw new IllegalArgumentException(
              "Objectify keys may only be used for the first argument");
        }
        lastKey = (Key) ancestor;
        continue;
      }

      // The argument should be a value.
      if (lastClass == null) {
        throw new IllegalArgumentException("Argument " + ancestor + " should be a class.");
      }
      if (ancestor instanceof Long) {
        lastKey = Key.create(lastKey, lastClass, (Long) ancestor);
      } else if (ancestor instanceof String) {
        lastKey = Key.create(lastKey, lastClass, (String) ancestor);
      } else {
        throw new IllegalArgumentException("Key value " + ancestor + " must be a string or long.");
      }
      lastClass = null;
    }

    // Make sure we didn't end up with a dangling class with no value.
    if (lastClass != null) {
      throw new IllegalArgumentException("Missing value for last key of type " + lastClass);
    }

    Object sqlKey = getSqlKey();
    Key<T> ofyKey =
        sqlKey instanceof Long
            ? Key.create(lastKey, getKind(), (Long) sqlKey)
            : Key.create(lastKey, getKind(), (String) sqlKey);

    return VKey.create((Class<T>) getKind(), sqlKey, ofyKey);
  }

  /**
   * Returns a clone of {@code key} with an ofy key restored from {@code ancestors}.
   *
   * <p>This is the static form of the method restoreOfy() above. If {@code key} is null, it returns
   * null.
   */
  public static <T> VKey<T> restoreOfyFrom(@Nullable VKey<T> key, Object... ancestors) {
    return key == null ? null : key.restoreOfy(ancestors);
  }

  /** Returns the type of the entity. */
  public Class<? extends T> getKind() {
    return this.kind;
  }

  /** Returns the SQL primary key. */
  public Object getSqlKey() {
    checkState(sqlKey != null, "Attempting obtain a null SQL key.");
    return this.sqlKey;
  }

  /** Returns the SQL primary key if it exists. */
  public Optional<Object> maybeGetSqlKey() {
    return Optional.ofNullable(this.sqlKey);
  }

  /** Returns the objectify key. */
  public Key<T> getOfyKey() {
    checkState(ofyKey != null, "Attempting obtain a null Objectify key.");
    return this.ofyKey;
  }

  /** Returns the objectify key if it exists. */
  public Optional<Key<T>> maybeGetOfyKey() {
    return Optional.ofNullable(this.ofyKey);
  }

  /** Convenience method to construct a VKey from an objectify Key. */
  @Nullable
  public static <T> VKey<T> from(Key<T> key) {
    return VKeyTranslatorFactory.createVKey(key);
  }

  /**
   * Construct a VKey from the string representation of an ofy key.
   *
   * <p>TODO(b/184350590): After migration, we'll want remove the ofy key dependency from this.
   */
  @Nullable
  public static <T> VKey<T> from(String ofyKeyRepr) {
    return from(Key.create(ofyKeyRepr));
  }
}
