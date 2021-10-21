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

package google.registry.model;

import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.transformValues;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Ignore;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.concurrent.Immutable;
import javax.xml.bind.annotation.XmlTransient;

/** An immutable object that implements {@link #equals}, {@link #hashCode} and {@link #toString}. */
@Immutable
@XmlTransient
public abstract class ImmutableObject implements Cloneable {

  /** Marker to indicate that {@link #toHydratedString} should not hydrate a field. */
  @Documented
  @Retention(RUNTIME)
  @Target(FIELD)
  public @interface DoNotHydrate {}

  /**
   * Indicates that the field should be ignored when comparing an object in the datastore to the
   * corresponding object in Cloud SQL.
   */
  @Documented
  @Retention(RUNTIME)
  @Target(FIELD)
  public @interface DoNotCompare {}

  /**
   * Indicates that the field stores a null value to indicate an empty set. This is also used in
   * object comparison.
   */
  @Documented
  @Retention(RUNTIME)
  @Target(FIELD)
  public @interface EmptySetToNull {}

  /**
   * Indicates that the field does not take part in the immutability contract.
   *
   * <p>Certain fields currently get modified by hibernate and there is nothing we can do about it.
   * As well as violating immutability, this breaks hashing and equality comparisons, so we mark
   * these fields with this annotation to exclude them from most operations.
   */
  @Documented
  @Retention(RUNTIME)
  @Target(FIELD)
  public @interface Insignificant {}

  // Note: if this class is made to implement Serializable, this field must become 'transient' since
  // hashing is not stable across executions. Also note that @XmlTransit is forbidden on transient
  // fields and need to be removed if transient is added.
  @Ignore @XmlTransient protected Integer hashCode;

  private boolean equalsImmutableObject(ImmutableObject other) {
    return getClass().equals(other.getClass())
        && hashCode() == other.hashCode()
        && getSignificantFields().equals(other.getSignificantFields());
  }

  /**
   * Returns the map of significant fields (fields that we care about for purposes of comparison and
   * display).
   *
   * <p>Isolated into a method so that derived classes can override it.
   */
  protected Map<Field, Object> getSignificantFields() {
    // Can't use streams or ImmutableMap because we can have null values.
    Map<Field, Object> result = new LinkedHashMap();
    for (Map.Entry<Field, Object> entry : ModelUtils.getFieldValues(this).entrySet()) {
      if (!entry.getKey().isAnnotationPresent(Insignificant.class)) {
        result.put(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ImmutableObject && equalsImmutableObject((ImmutableObject) other);
  }

  @Override
  public int hashCode() {
    if (hashCode == null) {
      hashCode = Arrays.hashCode(getSignificantFields().values().toArray());
    }
    return hashCode;
  }

  /** Returns a clone of the given object. */
  @SuppressWarnings("unchecked")
  protected static <T extends ImmutableObject> T clone(T t) {
    try {
      T clone = (T) t.clone();
      // Clear the hashCode since we often mutate clones before handing them out.
      clone.hashCode = null;
      return clone;
    } catch (CloneNotSupportedException e) {  // Yes it is.
      throw new IllegalStateException();
    }
  }

  /** Returns a clone of the given object with empty fields set to null. */
  protected static <T extends ImmutableObject> T cloneEmptyToNull(T t) {
    return ModelUtils.cloneEmptyToNull(t);
  }

  /**
   * Returns a string view of the object, formatted like:
   *
   * <pre>
   * ModelObject (@12345): {
   *   field1=value1
   *   field2=[a,b,c]
   *   field3=AnotherModelObject: {
   *     foo=bar
   *   }
   * }
   * </pre>
   */
  @Override
  public String toString() {
    NavigableMap<String, Object> sortedFields = new TreeMap<>();
    for (Entry<Field, Object> entry : getSignificantFields().entrySet()) {
      sortedFields.put(entry.getKey().getName(), entry.getValue());
    }
    return toStringHelper(sortedFields);
  }

  /** Similar to toString(), with a full expansion of referenced keys, including in collections. */
  public String toHydratedString() {
    // We can't use ImmutableSortedMap because we need to allow null values.
    NavigableMap<String, Object> sortedFields = new TreeMap<>();
    for (Entry<Field, Object> entry : getSignificantFields().entrySet()) {
      Field field = entry.getKey();
      Object value = entry.getValue();
      sortedFields.put(
          field.getName(), field.isAnnotationPresent(DoNotHydrate.class) ? value : hydrate(value));
    }
    return toStringHelper(sortedFields);
  }

  public String toStringHelper(SortedMap<String, Object> fields) {
    return String.format(
        "%s (@%s): {\n%s",
        getClass().getSimpleName(),
        System.identityHashCode(this),
        Joiner.on('\n').join(fields.entrySet()))
            .replaceAll("\n", "\n    ") + "\n}";
  }

  /** Helper function to recursively hydrate an ImmutableObject. */
  private static Object hydrate(Object value) {
    if (value instanceof Key) {
      if (tm().isOfy()) {
        return hydrate(auditedOfy().load().key((Key<?>) value).now());
      }
      return value;
    } else if (value instanceof Map) {
      return transformValues((Map<?, ?>) value, ImmutableObject::hydrate);
    } else if (value instanceof Collection) {
      return transform((Collection<?>) value, ImmutableObject::hydrate);
    } else if (value instanceof ImmutableObject) {
      return ((ImmutableObject) value).toHydratedString();
    }
    return value;
  }

  /** Helper function to recursively convert a ImmutableObject to a Map of generic objects. */
  private static Object toMapRecursive(Object o) {
    if (o == null) {
      return null;
    } else if (o instanceof ImmutableObject) {
      // LinkedHashMap to preserve field ordering and because ImmutableMap forbids null
      // values.
      Map<String, Object> result = new LinkedHashMap<>();
      for (Entry<Field, Object> entry : ((ImmutableObject) o).getSignificantFields().entrySet()) {
        Field field = entry.getKey();
        if (!field.isAnnotationPresent(IgnoredInDiffableMap.class)) {
          result.put(field.getName(), toMapRecursive(entry.getValue()));
        }
      }
      return result;
    } else if (o instanceof Map) {
      return Maps.transformValues((Map<?, ?>) o, ImmutableObject::toMapRecursive);
    } else if (o instanceof Set) {
      return ((Set<?>) o)
          .stream()
          .map(ImmutableObject::toMapRecursive)
          // We can't use toImmutableSet here, because values can be null (especially since the
          // original ImmutableObject might have been the result of a cloneEmptyToNull call).
          //
          // We can't use toSet either, because we want to preserve order. So we use LinkedHashSet
          // instead.
          .collect(toCollection(LinkedHashSet::new));
    } else if (o instanceof Collection) {
      return ((Collection<?>) o)
          .stream()
          .map(ImmutableObject::toMapRecursive)
          // We can't use toImmutableList here, because values can be null (especially since the
          // original ImmutableObject might have been the result of a cloneEmptyToNull call).
          .collect(toList());
    } else if (o instanceof Number || o instanceof Boolean) {
      return o;
    } else {
      return o.toString();
    }
  }

  /** Marker to indicate that this filed should be ignored by {@link #toDiffableFieldMap}. */
  @Documented
  @Retention(RUNTIME)
  @Target(FIELD)
  protected @interface IgnoredInDiffableMap {}

  /** Returns a map of all object fields (including sensitive data) that's used to produce diffs. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> toDiffableFieldMap() {
    return (Map<String, Object>) toMapRecursive(this);
  }
}
