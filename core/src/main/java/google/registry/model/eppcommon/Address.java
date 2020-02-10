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

package google.registry.model.eppcommon;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.annotation.AlsoLoad;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.JsonMapBuilder;
import google.registry.model.Jsonifiable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.persistence.Embeddable;
import javax.persistence.MappedSuperclass;
import javax.persistence.PostLoad;
import javax.persistence.Transient;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.CollapsedStringAdapter;
import javax.xml.bind.annotation.adapters.NormalizedStringAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Container for generic street address.
 *
 * <p>This is the "addrType" type from {@link "http://tools.ietf.org/html/rfc5733"}. It also matches
 * the "addrType" type from {@link "http://tools.ietf.org/html/draft-lozano-tmch-smd"}.
 *
 * @see google.registry.model.contact.ContactAddress
 * @see google.registry.model.mark.MarkAddress
 * @see google.registry.model.registrar.RegistrarAddress
 */
@XmlTransient
@Embeddable
@MappedSuperclass
public class Address extends ImmutableObject implements Jsonifiable {

  /** The schema validation will enforce that this has 3 lines at most. */
  // TODO(shicong): Remove this field after migration. We need to figure out how to generate same
  // XML from streetLine[1,2,3].
  @XmlJavaTypeAdapter(NormalizedStringAdapter.class)
  @Transient
  List<String> street;

  @Ignore @XmlTransient @IgnoredInDiffableMap String streetLine1;

  @Ignore @XmlTransient @IgnoredInDiffableMap String streetLine2;

  @Ignore @XmlTransient @IgnoredInDiffableMap String streetLine3;

  @XmlJavaTypeAdapter(NormalizedStringAdapter.class)
  String city;

  @XmlElement(name = "sp")
  @XmlJavaTypeAdapter(NormalizedStringAdapter.class)
  String state;

  @XmlElement(name = "pc")
  @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
  String zip;

  @XmlElement(name = "cc")
  @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
  String countryCode;

  public ImmutableList<String> getStreet() {
    if (street == null && streetLine1 != null) {
      return ImmutableList.of(streetLine1, nullToEmpty(streetLine2), nullToEmpty(streetLine3));
    } else {
      return nullToEmptyImmutableCopy(street);
    }
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

  /** A builder for constructing {@link Address}. */
  public static class Builder<T extends Address> extends Buildable.Builder<T> {

    public Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    public Builder<T> setStreet(ImmutableList<String> street) {
      checkArgument(
          street == null || (!street.isEmpty() && street.size() <= 3),
          "Street address must have [1-3] lines: %s", street);
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
   * Sets {@link #streetLine1}, {@link #streetLine2} and {@link #streetLine3} after loading the
   * entity from Datastore.
   *
   * <p>This callback method is used by Objectify to set streetLine[1,2,3] fields as they are not
   * persisted in the Datastore. TODO(shicong): Delete this method after database migration.
   */
  void onLoad(@AlsoLoad("street") List<String> street) {
    if (street == null || street.size() == 0) {
      return;
    }
    streetLine1 = street.get(0);
    streetLine2 = street.size() >= 2 ? street.get(1) : null;
    streetLine3 = street.size() >= 3 ? street.get(2) : null;
  }

  /**
   * Sets {@link #street} after loading the entity from Cloud SQL.
   *
   * <p>This callback method is used by Hibernate to set {@link #street} field as it is not
   * persisted in Cloud SQL. We are doing this because the street list field is exposed by Address
   * class and is used everywhere in our code base. Also, setting/reading a list of strings is more
   * convenient.
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
}
