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

package google.registry.model.tld.label;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.emptyToNull;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.Buildable.GenericBuilder;
import google.registry.model.ImmutableObject;
import javax.persistence.Column;
import javax.persistence.MappedSuperclass;

/**
 * Represents a label entry parsed from a line in a reserved/premium list txt file.
 *
 * @param <T> The type of the value stored for the domain label, e.g. {@link ReservationType}.
 */
@MappedSuperclass
public abstract class DomainLabelEntry<T extends Comparable<?>, D extends DomainLabelEntry<?, ?>>
    extends ImmutableObject implements Comparable<D> {

  @Id
  @javax.persistence.Id
  @Column(name = "domainLabel", nullable = false)
  String domainLabel;

  /**
   * Returns the label of the field, which also happens to be used as the key for the Map object
   * that is serialized from Datastore.
   */
  public String getDomainLabel() {
    return domainLabel;
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
      getInstance().domainLabel = label;
      return thisCastToDerived();
    }

    @Override
    public T build() {
      checkArgumentNotNull(emptyToNull(getInstance().domainLabel), "Label must be specified");
      checkArgument(
          getInstance().domainLabel.equals(canonicalizeDomainName(getInstance().domainLabel)),
          "Label '%s' must be in puny-coded, lower-case form",
          getInstance().domainLabel);
      checkArgumentNotNull(getInstance().getValue(), "Value must be specified");
      // Verify that the label creates a valid SLD if we add a TLD to the end of it.
      // We require that the label is not already a full domain name including a dot.
      // Domain name validation is tricky, so let InternetDomainName handle it for us.
      checkArgument(
          InternetDomainName.from(getInstance().domainLabel + ".tld").parts().size() == 2,
          "Label %s must not be a multi-level domain name",
          getInstance().domainLabel);
      return super.build();
    }
  }
}
