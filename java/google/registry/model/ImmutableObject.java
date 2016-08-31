// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import static com.google.common.base.Functions.identity;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Maps.transformValues;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Maps;
import com.googlecode.objectify.Ref;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.domain.ReferenceUnion;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.concurrent.Immutable;
import javax.xml.bind.annotation.XmlTransient;

/** An immutable object that implements {@link #equals}, {@link #hashCode} and {@link #toString}. */
@Immutable
@XmlTransient
public abstract class ImmutableObject implements Cloneable {

  /** Marker to indicate that {@link #toHydratedString} should not hydrate a field of this type. */
  @Documented
  @Retention(RUNTIME)
  @Target(TYPE)
  public static @interface DoNotHydrate {}

  @Ignore
  @XmlTransient
  Integer hashCode;

  private boolean equalsImmutableObject(ImmutableObject other) {
    return getClass().equals(other.getClass())
        && hashCode() == other.hashCode()
        && ModelUtils.getFieldValues(this).equals(ModelUtils.getFieldValues(other));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ImmutableObject && equalsImmutableObject((ImmutableObject) other);
  }

  @Override
  public int hashCode() {
    if (hashCode == null) {
      hashCode = Arrays.hashCode(ModelUtils.getFieldValues(this).values().toArray());
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
    return toStringHelper(identity());
  }

  /**
   * Similar to toString(), with a full expansion of embedded ImmutableObjects,
   * collections, and references.
   */
  public String toHydratedString() {
    return toStringHelper(new Function<Object, Object>() {
        @Override
        public Object apply(Object input) {
          if (input instanceof ReferenceUnion) {
            return apply(((ReferenceUnion<?>) input).getLinked().get());
          } else if (input instanceof Ref) {
            // Only follow references of type Ref, not of type Key (the latter deliberately used for
            // references that should not be followed)
            Object target = ((Ref<?>) input).get();
            return target != null && target.getClass().isAnnotationPresent(DoNotHydrate.class)
                ? input
                : apply(target);
          } else if (input instanceof Map) {
            return transformValues((Map<?, ?>) input, this);
          } else if (input instanceof Collection) {
            return transform((Collection<?>) input, this);
          } else if (input instanceof ImmutableObject) {
            return ((ImmutableObject) input).toHydratedString();
          }
          return input;
        }});
  }

  public String toStringHelper(Function<Object, Object> transformation) {
    Map<String, Object> sortedFields = Maps.newTreeMap();
    sortedFields.putAll(
        transformValues(ModelUtils.getFieldValues(this), transformation));
    return String.format(
        "%s (@%s): {\n%s",
        getClass().getSimpleName(),
        System.identityHashCode(this),
        Joiner.on('\n').join(sortedFields.entrySet()))
            .replaceAll("\n", "\n    ") + "\n}";
  }

  /** Helper function to recursively convert a ImmutableObject to a Map of generic objects. */
  private static final Function<Object, Object> TO_MAP_HELPER = new Function<Object, Object>() {
    @Override
    public Object apply(Object o) {
      if (o == null) {
        return null;
      } else if (o instanceof ImmutableObject) {
        Map<String, Object> result =
            Maps.transformValues(ModelUtils.getFieldValues(o), this);
        return result;
      } else if (o instanceof Map) {
        return Maps.transformValues((Map<?, ?>) o, this);
      } else if (o instanceof Set) {
        return FluentIterable.from((Set<?>) o).transform(this).toSet();
      } else if (o instanceof Collection) {
        return FluentIterable.from((Collection<?>) o).transform(this).toList();
      } else if (o instanceof Number || o instanceof Boolean) {
        return o;
      } else {
        return o.toString();
      }
    }};

  /** Returns a map of all object fields (including sensitive data) that's used to produce diffs. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> toDiffableFieldMap() {
    return (Map<String, Object>) TO_MAP_HELPER.apply(this);
  }
}
