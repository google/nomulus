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

package google.registry.model.eppcommon;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.gson.annotations.Expose;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.JsonMapBuilder;
import google.registry.model.Jsonifiable;
import google.registry.model.UnsafeSerializable;
import google.registry.tools.GsonUtils.GsonPostProcessable;
import jakarta.persistence.Embeddable;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Transient;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter;
import jakarta.xml.bind.annotation.adapters.NormalizedStringAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Container for generic street address.
 *
 * <p>This is the "addrType" type from <a href="http://tools.ietf.org/html/rfc5733">RFC5733</a>. It
 * also matches the "addrType" type from <a
 * href="http://tools.ietf.org/html/draft-lozano-tmch-smd">Mark and Signed Mark Objects Mapping</a>.
 *
 * @see google.registry.model.contact.ContactAddress
 * @see google.registry.model.mark.MarkAddress
 * @see google.registry.model.registrar.RegistrarAddress
 */
@XmlTransient
@Embeddable
@MappedSuperclass
public class Address extends ImmutableObject
    implements Jsonifiable, UnsafeSerializable, GsonPostProcessable {

  /**
   * At most three lines of addresses parsed from XML elements.
   *
   * <p>This field is used to marshal to/unmarshal from XML elements. When persisting to/from SQL,
   * the next three separate fields are used. Those lines are <em>only</em> used for persistence
   * purpose and should not be directly used in Java.
   *
   * <p>We need to keep the list and the three fields in sync in all the following scenarios when an
   * entity containing a {@link Address} is created:
   *
   * <ul>
   *   <li>When creating an {@link Address} directly in java, using the {@link Builder}: The {@link
   *       Builder#setStreet(ImmutableList)} sets both the list and the fields.
   *   <li>When unmarshalling from XML: The list will be set based on the content of the XML.
   *       Afterwards, {@link #afterUnmarshal(Unmarshaller, Object)}} will be called to set the
   *       fields.
   *   <li>When loading from the database: The fields will be set by the values in SQL. Afterwards,
   *       {@link #postLoad()} will be called to set the list.
   * </ul>
   *
   * The syncing is especially important because when merging a detached entity into a session, JPA
   * provides no guarantee that transient fields will be preserved. In fact, Hibernate chooses to
   * discard them when returning a newly merged entity. This means that it is not enough to provide
   * callbacks to populate the fields based on the list before persistence, as merging does not
   * invoke the callbacks, and we would lose the address fields if only the list is set. Instead,
   * the fields must be populated when the list is.
   *
   * <p>Schema validation will enforce the 3-line limit.
   */
  @XmlJavaTypeAdapter(NormalizedStringAdapter.class)
  @Transient
  @Expose
  protected List<String> street;

  @XmlTransient @IgnoredInDiffableMap protected String streetLine1;

  @XmlTransient @IgnoredInDiffableMap protected String streetLine2;

  @XmlTransient @IgnoredInDiffableMap protected String streetLine3;

  @XmlJavaTypeAdapter(NormalizedStringAdapter.class)
  @Expose
  protected String city;

  @XmlElement(name = "sp")
  @XmlJavaTypeAdapter(NormalizedStringAdapter.class)
  @Expose
  protected String state;

  @XmlElement(name = "pc")
  @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
  @Expose
  protected String zip;

  @XmlElement(name = "cc")
  @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
  @Expose
  protected String countryCode;

  public ImmutableList<String> getStreet() {
    return nullToEmptyImmutableCopy(street);
  }

  public String getCity() {
    return city;
  }

  public String getState() {
    return state;
  }

  public String getZip() {
    return zip;
  }

  public String getCountryCode() {
    return countryCode;
  }

  @Override
  public Map<String, Object> toJsonMap() {
    return new JsonMapBuilder()
        .putListOfStrings("street", street)
        .put("city", city)
        .put("state", state)
        .put("zip", zip)
        .put("countryCode", countryCode)
        .build();
  }

  @VisibleForTesting
  public Builder<? extends Address> asBuilder() {
    return new Builder<>(clone(this));
  }

  @Override
  public void postProcess() {
    if (street == null || street.isEmpty()) {
      return;
    }
    streetLine1 = street.get(0);
    streetLine2 = street.size() >= 2 ? street.get(1) : null;
    streetLine3 = street.size() >= 3 ? street.get(2) : null;
  }

  /** A builder for constructing {@link Address}. */
  public static class Builder<T extends Address> extends Buildable.Builder<T> {

    public Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    public Builder<T> setStreet(ImmutableList<String> street) {
      checkArgument(
          street == null || (!street.isEmpty() && street.size() <= 3),
          "Street address must have [1-3] lines: %s",
          street);
      //noinspection ConstantConditions
      checkArgument(
          street.stream().noneMatch(String::isEmpty),
          "Street address cannot contain empty string: %s",
          street);
      getInstance().street = street;
      getInstance().streetLine1 = street.get(0);
      getInstance().streetLine2 = street.size() >= 2 ? street.get(1) : null;
      getInstance().streetLine3 = street.size() == 3 ? street.get(2) : null;
      return this;
    }

    public Builder<T> setCity(String city) {
      getInstance().city = city;
      return this;
    }

    public Builder<T> setState(String state) {
      getInstance().state = state;
      return this;
    }

    public Builder<T> setZip(String zip) {
      getInstance().zip = zip;
      return this;
    }

    public Builder<T> setCountryCode(String countryCode) {
      checkArgument(
          countryCode == null || countryCode.length() == 2,
          "Country code should be a 2 character string");
      getInstance().countryCode = countryCode;
      return this;
    }
  }

  /**
   * Sets {@link #street} after loading the entity from Cloud SQL.
   *
   * <p>This callback method is used by Hibernate to set the {@link #street} field as it is not
   * persisted in Cloud SQL. We are doing this because this field is exposed and used everywhere in
   * our code base, whereas the individual {@code streetLine} fields are only used by Hibernate for
   * persistence. Also, setting/reading a list of strings is more convenient.
   */
  @PostLoad
  void postLoad() {
    street =
        streetLine1 == null
            ? null
            : Stream.of(streetLine1, streetLine2, streetLine3)
                .filter(Objects::nonNull)
                .collect(toImmutableList());
  }

  /**
   * Sets {@link #streetLine1}, {@link #streetLine2} and {@link #streetLine3} when the entity is
   * reconstructed from XML message.
   *
   * <p>This is a callback function that JAXB invokes after unmarshalling the XML message.
   */
  @SuppressWarnings("unused")
  void afterUnmarshal(Unmarshaller unmarshaller, Object parent) {
    postProcess();
  }
}
