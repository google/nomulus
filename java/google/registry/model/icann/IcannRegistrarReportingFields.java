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

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;

import org.joda.time.YearMonth;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.common.EntityGroupRoot;

import static com.google.cloud.sql.jdbc.internal.Util.checkNotNull;
import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;

/**
 * This represents the ICANN reporting details we can not derive from our datastore.
 * User will enter in client id, tld and the counts for the 3 icann report fields
 * 'ramp-Up-Registrars', 'pre-Ramp-Up-Registrars' and 'zfa-Passwords' and
 * the year and month for the ICANN report.
 */
@Entity
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
public class IcannRegistrarReportingFields extends ImmutableObject
    implements Buildable, Comparable<IcannRegistrarReportingFields> {

  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();

  /**
   * client id for this IcannRegistrarReportingFields.
   */
  @Id
  @Index
  private String clientId;

  /**
   * Top Level Domain Name
   */
  @Index
  private String tld;

  /**
   * RampUpRegistrarsCount for IcannRegistrarReportingFields
   */
  private int rampUpRegistrarsCount;

  /**
   * PreRampUpRegistrarsCount for IcannRegistrarReportingFields
   */
  private int preRampUpRegistrarsCount;

  /**
   * ZfaPasswordsCount for IcannRegistrarReportingFields
   */
  private int zfaPasswordsCount;

  /**
   * Year and Month of IcannTldReportingFields
   */
  @Index
  private YearMonth yearMonth;

  public String getClientId() {
    return clientId;
  }

  public String getTld() {
    return tld;
  }

  public int getRampUpRegistrarsCount() {
    return rampUpRegistrarsCount;
  }

  public int getPreRampUpRegistrarsCount() {
    return preRampUpRegistrarsCount;
  }

  public int getZfaPasswordsCount() {
    return zfaPasswordsCount;
  }

  public YearMonth getYearMonth() {
    return yearMonth;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  @Override
  public int compareTo(IcannRegistrarReportingFields o) {
    return o.getClientId().compareTo(this.getClientId());
  }

  public static class Builder extends Buildable.Builder<IcannRegistrarReportingFields> {
    public Builder() {
    }

    public Builder(IcannRegistrarReportingFields instance) {
      super(instance);
    }

    public Builder setClientId(String clientId) {
      getInstance().clientId = clientId;
      return this;
    }

    public Builder setTld(String tld) {
      getInstance().tld = tld;
      return this;
    }

    public Builder setRampUpRegistrarsCount(int rampUpRegistrarsCount) {
      getInstance().rampUpRegistrarsCount = rampUpRegistrarsCount;
      return this;
    }

    public Builder setPreRampUpRegistrarsCount(int preRampUpRegistrarsCount) {
      getInstance().preRampUpRegistrarsCount = preRampUpRegistrarsCount;
      return this;
    }

    public Builder setZfaPasswordsCount(int zfaPasswordsCount) {
      getInstance().zfaPasswordsCount = zfaPasswordsCount;
      return this;
    }

    public Builder setYearMonth(YearMonth yearMonth) {
      getInstance().yearMonth = yearMonth;
      return this;
    }

    public IcannRegistrarReportingFields build() {
      final IcannRegistrarReportingFields instance = getInstance();

      checkNotNull(instance.getClientId(), "Client id cannot be null.");
      checkNotNull(instance.getTld(), "Tld name cannot be null.");
      checkArgument(instance.getRampUpRegistrarsCount() >= 0, "RampUpRegistrarsCount cannot be negative.");
      checkArgument(instance.getPreRampUpRegistrarsCount() >= 0, "PreRampUpRegistrarsCount cannot be negative.");
      checkArgument(instance.getZfaPasswordsCount() >= 0, "ZfaPasswordsCount cannot be negative.");
      checkNotNull(instance.getYearMonth(), "Year-month cannot be null.");
      checkArgument(instance.getYearMonth().size() != 6, "Year-month cannot be less than 6 digits.");

      return super.build();
    }
  }
}
