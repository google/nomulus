/*
 *  Copyright 2016 The Domain Registry Authors. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package google.registry.model.icann;

import com.google.common.collect.ImmutableSet;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;

import java.util.Set;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.common.EntityGroupRoot;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;

/**
 * A class to represent the ICANN reporting details we can not derive from our datastore.
 * User will enter in Name of ICANN report field with the count associated with that field.
 */
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@Entity
public class IcannReportField extends ImmutableObject
    implements Buildable, Comparable<IcannReportField> {

  private final static Set<String> REGISTRAR_VALID_NAMES = ImmutableSet.of(
      "ramp-up-registrars", "pre-ramp-up-registrars", "zfa-passwords");


  //Set of valid field names for DNS Transaction Report
  private final static Set<String> DNS_VALID_NAMES =
      ImmutableSet.of("dns-udp-queries-received", "dns-udp-queries-responded",
          "dns-tcp-queries-received", "dns-tcp-queries-responded");

  //Set of valid field names for TLD Transaction Report
  private final static Set<String> TLD_VALID_NAMES =
      ImmutableSet.of("transfer-disputed-won", "transfer-disputed-lost",
          "transfer-disputed-nondecision", "agp-exemption-requests",
          "agp-exemptions-granted", "agp-exempted-domains");

  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();

  /**
   * Universally unique id for this IcannReportField.
   */
  @Id
  private String id;

  /**
   * Top Level Domain Name
   */
  private String tld;

  /**
   * Name of IcannReportField
   */
  @Index
  private String name;

  /**
   * Count for IcannReportField
   */
  private Integer count;

  /**
   * Year(yyyy) of IcannReportField
   */
  private String year;

  /**
   * Month(mm) of IcannReportField
   */
  private String month;

  /**
   * Type of IcannReportField
   */
  private Type type;

  public String getId() {
    return id;
  }

  public String getTld() {
    return tld;
  }

  public String getName() {
    return name;
  }

  public Integer getCount() {
    return count;
  }

  public String getYear() {
    return year;
  }

  public String getMonth() {
    return month;
  }

  public Type getType() {
    return type;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  @Override
  public int compareTo(IcannReportField o) {
    return o.getName().compareTo(this.getName());
  }

  public enum Type {
    REGISTRAR,
    DNS,
    TLD
  }

  public static class Builder extends Buildable.Builder<IcannReportField> {
    public Builder() {
    }

    public Builder(IcannReportField instance) {
      super(instance);
    }

    public Builder setId(String id) {
      getInstance().id = id;
      return this;
    }

    public Builder setTld(String tld) {
      getInstance().tld = tld;
      return this;
    }

    public Builder setName(String name) {
      getInstance().name = name;
      return this;
    }

    public Builder setCount(Integer count) {
      getInstance().count = count;
      return this;
    }

    public Builder setYear(String year) {
      getInstance().year = year;
      return this;
    }

    public Builder setMonth(String month) {
      getInstance().month = month;
      return this;
    }

    public Builder setType(Type type) {
      getInstance().type = type;
      return this;
    }

    public IcannReportField build() {
      final IcannReportField instance = getInstance();

      checkArgument(instance.getId() != null, "Id cannot be null.");
      checkArgument(instance.getName() != null, "Name cannot be null.");
      checkArgument(instance.getCount() != null, "Count cannot be null.");
      checkArgument(instance.getCount() > 0, "Count cannot be negative.");
      checkArgument(instance.getYear() != null, "Year cannot be null.");
      checkArgument(instance.getYear().length() == 4, "Year must be four characters.");
      checkArgument(instance.getMonth() != null, "Month cannot be null.");
      checkArgument(instance.getMonth().length() == 2, "Month must be two characters.");
      checkArgument(instance.getType() != null, "Type cannot be null.");


      if (instance.type.equals(Type.REGISTRAR)
          && !REGISTRAR_VALID_NAMES.contains(instance.getName())) {
        throw new IllegalArgumentException("Icann name must use a valid field name. Invalid name: "
            + instance.getName());
      } else if (instance.type.equals(Type.DNS)
          && !DNS_VALID_NAMES.contains(instance.getName())) {
        throw new IllegalArgumentException("Icann name must use a valid field name. Invalid name: "
            + instance.getName());
      } else if (instance.type.equals(Type.TLD)
          && !TLD_VALID_NAMES.contains(instance.getName())) {
        throw new IllegalArgumentException("Icann name must use a valid field name. Invalid name: "
            + instance.getName());
      }

      return super.build();
    }
  }
}
