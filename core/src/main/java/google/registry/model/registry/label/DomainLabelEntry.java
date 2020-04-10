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

package google.registry.model.registry.label;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.emptyToNull;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.googlecode.objectify.annotation.Id;
import google.registry.model.Buildable.GenericBuilder;
import google.registry.model.ImmutableObject;

/**
 * Represents a label entry parsed from a line in a reserved/premium list txt file.
 *
 * @param <T> The type of the value stored for the domain label, e.g. {@link ReservationType}.
 */
public abstract class DomainLabelEntry<T extends Comparable<?>, D extends DomainLabelEntry<?, ?>>
    extends ImmutableObject implements Comparable<D> {

  @Id
  String label;

  String comment;

  /**
   * Returns the label of the field, which also happens to be used as the key for the Map object
   * that is serialized from Datastore.
   */
  public String getLabel() {
    return label;
  }

  /**
   * Returns the value of the field (used for determining which entry takes priority over another).
   */
  public abstract T getValue();

  @Override
  @SuppressWarnings("unchecked")
  public int compareTo(D other) {
    return ((Comparable<Object>) getValue()).compareTo(other.getValue());
  }

  /** A generic builder base. */
  public abstract static class Builder<T extends DomainLabelEntry<?, ?>, B extends Builder<T, ?>>
      extends GenericBuilder<T, B> {

    public Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    public B setLabel(String label) {
      getInstance().label = label;
      return thisCastToDerived();
    }

    public B setComment(String comment) {
      getInstance().comment = comment;
      return thisCastToDerived();
    }

    @Override
    public T build() {
      checkArgumentNotNull(emptyToNull(getInstance().label), "Label must be specified");
      checkArgument(
          getInstance().label.equals(canonicalizeDomainName(getInstance().label)),
          "Label '%s' must be in puny-coded, lower-case form",
          getInstance().label);
      checkArgumentNotNull(getInstance().getValue(), "Value must be specified");
      checkArgument(
          !getInstance().label.contains("."),
          "Label %s must not be a multi-level domain name",
          getInstance().label);
      return super.build();
    }
  }
}
