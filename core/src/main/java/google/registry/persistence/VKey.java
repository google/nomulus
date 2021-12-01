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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.BackupGroupRoot;
import google.registry.model.ImmutableObject;
import google.registry.model.translators.VKeyTranslatorFactory;
import google.registry.util.SerializeUtils;
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

  // Info that's stored in in vkey string generated via stringify().
  private static final String SQL_LOOKUP_KEY = "sql";
  private static final String OFY_LOOKUP_KEY = "ofy";
  private static final String CLASS_TYPE = "kind";

  // Web safe delimiters that won't be used in base 64.
  private static final String KV_SEPARATOR = ":";
  private static final String DELIMITER = "@";

  // The SQL key for the referenced entity.
  Serializable sqlKey;

  // The objectify key for the referenced entity.
  Key<T> ofyKey;

  Class<? extends T> kind;

  VKey() {}

  VKey(Class<? extends T> kind, Key<T> ofyKey, Serializable sqlKey) {
    this.kind = kind;
    this.ofyKey = ofyKey;
    this.sqlKey = sqlKey;
  }

  /**
   * Creates a {@link VKey} which only contains the sql primary key.
   *
   * <p>Deprecated. Create symmetric keys with create() instead.
   */
  public static <T> VKey<T> createSql(Class<T> kind, Serializable sqlKey) {
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
  public static <T> VKey<T> create(Class<T> kind, Serializable sqlKey, Key<T> ofyKey) {
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
   * {@link #create(Class, Serializable, Key)} instead and pass in the full, valid parent field in
   * the Datastore key.
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
   * {@link #create(Class, Serializable, Key)} instead and pass in the full, valid parent field in
   * the Datastore key.
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
   * Constructs a {@link VKey} from the string representation of a vkey.
   *
   * <p>There are two types of string representations: 1) existing ofy key string handled by
   * fromWebsafeKey() and 2) string encoded via stringify() where @ separates the substrings and
   * each of the substrings contains a look up key, ":", and its corresponding value. The key info
   * is encoded via Base64. The string begins with "kind:" and it must contains at least ofy key or
   * sql key.
   *
   * <p>Example of a Vkey string by fromWebsafeKey(): "agR0ZXN0chYLEgpEb21haW5CYXNlIgZST0lELTEM"
   *
   * <p>Example of a vkey string by stringify(): "google.registry.testing.TestObject@sql:rO0ABX" +
   * "QAA2Zvbw@ofy:agR0ZXN0cjELEg9FbnRpdHlHcm91cFJvb3QiCWNyb3NzLXRsZAwLEgpUZXN0T2JqZWN0IgNmb28M",
   * where sql key and ofy key are values are encoded in Base64.
   */
  public static <T> VKey<T> create(String keyString) throws Exception {
    if (!keyString.startsWith(CLASS_TYPE + KV_SEPARATOR)) {
      // to handle the existing ofy key string
      return fromWebsafeKey(keyString);
    } else {
      ImmutableMap<String, String> kvs =
          ImmutableMap.copyOf(
              Splitter.on(DELIMITER).withKeyValueSeparator(KV_SEPARATOR).split(keyString));
      Class classType = Class.forName(kvs.get(CLASS_TYPE));

      if (kvs.containsKey(SQL_LOOKUP_KEY) && kvs.containsKey(OFY_LOOKUP_KEY)) {
        return VKey.create(
            classType,
            SerializeUtils.parse(Serializable.class, kvs.get(SQL_LOOKUP_KEY)),
            Key.create(kvs.get(OFY_LOOKUP_KEY)));
      } else if (kvs.containsKey(SQL_LOOKUP_KEY)) {
        return VKey.createSql(
            classType, SerializeUtils.parse(Serializable.class, kvs.get(SQL_LOOKUP_KEY)));
      } else if (kvs.containsKey(OFY_LOOKUP_KEY)) {
        return VKey.createOfy(classType, Key.create(kvs.get(OFY_LOOKUP_KEY)));
      } else {
        throw new IllegalArgumentException(String.format("Cannot parse key string: %s", keyString));
      }
    }
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

    Serializable sqlKey = getSqlKey();
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
  public Serializable getSqlKey() {
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
  public static <T> VKey<T> fromWebsafeKey(String ofyKeyRepr) {
    return from(Key.create(ofyKeyRepr));
  }

  /**
   * Constructs the string representation of a {@link VKey}.
   *
   * <p>The string representation of a vkey contains its type, and sql key or ofy key, or both. Each
   * of the keys is first serialized into a byte array then encoded via Base64 into a web safe
   * string.
   *
   * <p>The string representation of a vkey contains key values pairs separated by delimiter "@".
   * Another delimiter ":" is put in between each key and value. The following is the complete
   * format of the string: "kind:class_name@sql:encoded_sqlKey@ofy:encoded_ofyKey", where kind is
   * required. The string representation may contain an encoded ofy key, or an encoded sql key, or
   * both.
   */
  public String stringify() {
    // class type is required to create a vkey
    String key = CLASS_TYPE + KV_SEPARATOR + getKind().getName();
    if (maybeGetSqlKey().isPresent()) {
      key += DELIMITER + SQL_LOOKUP_KEY + KV_SEPARATOR + SerializeUtils.stringify(getSqlKey());
    }
    if (maybeGetOfyKey().isPresent()) {
      key += DELIMITER + OFY_LOOKUP_KEY + KV_SEPARATOR + getOfyKey().getString();
    }
    return key;
  }

  /**
   * Constructs the readable string representation of a {@link VKey}.
   *
   * <p>This readable string representation of a vkey contains its type and its sql key or ofy key,
   * or both.
   */
  @Override
  public String toString() {
    if (maybeGetOfyKey().isPresent() && maybeGetSqlKey().isPresent()) {
      return String.format(
          "VKey<%s>(%s:%s,%s:%s)",
          getKind().getSimpleName(), SQL_LOOKUP_KEY, sqlKey, OFY_LOOKUP_KEY, ofyKeyToString());
    } else if (maybeGetSqlKey().isPresent()) {
      return String.format("VKey<%s>(%s:%s)", getKind().getSimpleName(), SQL_LOOKUP_KEY, sqlKey);
    } else if (maybeGetOfyKey().isPresent()) {
      return String.format("VKey<%s>(%s:%s)", ofyKey.getKind(), OFY_LOOKUP_KEY, ofyKeyToString());
    } else {
      throw new IllegalStateException("VKey should contain at least one form of key");
    }
  }

  private String ofyKeyToString() {
    return ofyKey.getName() == null ? String.valueOf(ofyKey.getId()) : ofyKey.getName();
  }
}
