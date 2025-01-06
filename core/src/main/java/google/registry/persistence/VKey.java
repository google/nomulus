// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.SafeSerializationUtils.safeDeserialize;
import static google.registry.util.SerializeUtils.decodeBase64;
import static java.util.function.Function.identity;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.annotations.Expose;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.util.SerializeUtils;
import java.io.Serializable;

/**
 * VKey is an abstraction that encapsulates the key concept.
 *
 * <p>A VKey instance must contain the JPA primary key for the referenced entity class.
 */
public class VKey<T> extends ImmutableObject implements Serializable {

  private static final long serialVersionUID = -5291472863840231240L;

  // Info that's stored in VKey string generated via stringify().
  private static final String LOOKUP_KEY = "sql";
  private static final String CLASS_TYPE = "kind";

  // Web safe delimiters that won't be used in base 64.
  private static final String KV_SEPARATOR = ":";
  private static final String DELIMITER = "@";

  private static final ImmutableMap<String, Class<? extends EppResource>> EPP_RESOURCE_CLASS_MAP =
      ImmutableList.of(Domain.class, Host.class, Contact.class).stream()
          .collect(toImmutableMap(Class::getSimpleName, identity()));

  // The primary key for the referenced entity.
  @Expose Serializable key;

  @Expose Class<? extends T> kind;

  @SuppressWarnings("unused")
  VKey() {}

  VKey(Class<? extends T> kind, Serializable key) {
    this.kind = kind;
    this.key = key;
  }

  /** Creates a {@link VKey} with supplied the SQL primary key. */
  public static <T> VKey<T> create(Class<T> kind, Serializable key) {
    checkArgumentNotNull(kind, "kind must not be null");
    checkArgumentNotNull(key, "key must not be null");
    return new VKey<>(kind, key);
  }

  /**
   * Constructs a {@link VKey} for an {@link EppResource } from the string representation.
   *
   * <p>The string representation is obtained from the {@link #stringify()} function and like this:
   * {@code kind:SomeEntity@sql:rO0ABXQAA2Zvbw}
   */
  public static <T extends EppResource> VKey<T> createEppVKeyFromString(String keyString) {
    ImmutableMap<String, String> kvs =
        ImmutableMap.copyOf(
            Splitter.on(DELIMITER).withKeyValueSeparator(KV_SEPARATOR).split(keyString));
    String classString = kvs.get(CLASS_TYPE);
    if (classString == null) {
      throw new IllegalArgumentException(
          String.format("\"%s\" missing from the string: %s", CLASS_TYPE, keyString));
    }
    @SuppressWarnings("unchecked")
    Class<T> classType = (Class<T>) EPP_RESOURCE_CLASS_MAP.get(classString);
    if (classType == null) {
      throw new IllegalArgumentException(String.format("%s is not an EppResource", classString));
    }
    String encodedString = kvs.get(LOOKUP_KEY);
    if (encodedString == null) {
      throw new IllegalArgumentException(
          String.format("\"%s\" missing from the string: %s", LOOKUP_KEY, keyString));
    }
    return VKey.create(classType, safeDeserialize(decodeBase64(kvs.get(LOOKUP_KEY))));
  }

  /** Returns the type of the entity. */
  public Class<? extends T> getKind() {
    return this.kind;
  }

  /** Returns the primary key. */
  public Serializable getKey() {
    return this.key;
  }

  /**
   * Constructs the string representation of a {@link VKey}.
   *
   * <p>The string representation contains its kind and Base64 SQL key, in the following format:
   * {@code kind:class_name@sql:encoded_sqlKey}.
   */
  public String stringify() {
    return Joiner.on(DELIMITER)
        .join(
            CLASS_TYPE + KV_SEPARATOR + getKind().getSimpleName(),
            LOOKUP_KEY + KV_SEPARATOR + SerializeUtils.stringify(getKey()));
  }

  /** Constructs the readable string representation of a {@link VKey}. */
  @Override
  public String toString() {
    return String.format("VKey<%s>(%s:%s)", getKind().getSimpleName(), LOOKUP_KEY, key);
  }
}
