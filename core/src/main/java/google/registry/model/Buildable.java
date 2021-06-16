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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.IdService.allocateId;
import static google.registry.model.ModelUtils.getAllFields;

import com.googlecode.objectify.annotation.Id;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.lang.reflect.Field;
import java.util.Optional;

/** Interface for {@link ImmutableObject} subclasses that have a builder. */
public interface Buildable {

  Builder<?> asBuilder();

  /**
   * Boilerplate for immutable builders.
   *
   * <p>This can be used without implementing {@link Buildable}.
   */
  abstract class Builder<S> {

    private S instance;

    protected Builder() {
      this.instance = new TypeInstantiator<S>(getClass()){}.instantiate();
      // Only ImmutableObject is allowed, but enforcing that via the generics gets ugly.
      checkState(instance instanceof ImmutableObject);
    }

    protected Builder(S instance) {
      this.instance = checkNotNull(instance);
    }

    protected S getInstance() {
      return checkNotNull(instance, "Cannot modify state after calling 'build()'.");
    }

    /** Build the instance. */
    public S build() {
      try {
        // If this object has a Long or long Objectify @Id field that is not set, set it now. For
        // any entity it has one and only one @Id field in its class hierarchy.
        Field idField =
            getAllFields(instance.getClass()).values().stream()
                .filter(field -> field.isAnnotationPresent(Id.class))
                .findFirst()
                .orElse(null);
        if (idField != null
            && !idField.getType().equals(String.class)
            && Optional.ofNullable((Long) ModelUtils.getFieldValue(instance, idField))
                .orElse(0L) == 0) {
          ModelUtils.setFieldValue(instance, idField, allocateId());
        }
        return instance;
      } finally {
        // Clear the internal instance so you can't accidentally mutate it through this builder.
        instance = null;
      }
    }
  }

  /** Boilerplate for abstract immutable builders that need to be able to cast "this". */
  abstract class GenericBuilder<S, B extends GenericBuilder<?, ?>> extends Builder<S> {
    protected GenericBuilder() {}

    protected GenericBuilder(S instance) {
      super(instance);
    }

    @SuppressWarnings("unchecked")
    protected B thisCastToDerived() {
      return (B) this;
    }
  }

  /**
   * Interface for objects that can produce an "overlay", which means a copy where non-null fields
   * from another object are copied over, but null fields on the source are not.
   *
   * <p>Warning: Do not use {@code emptyToNull} methods in the getters of an {@link Overlayable}! We
   * use null to mean "skip this field" whereas empty means "set this field to empty", so they are
   * semantically different.
   *
   * @param <T> the derived type
   */
  interface Overlayable<T> extends Buildable {
    /** Return an overlay of this object using non-null fields from the source. */
    T overlay(T source);
  }
}
