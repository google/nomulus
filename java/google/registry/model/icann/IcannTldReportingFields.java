/*
 *
 *  *  Copyright 2016 The Domain Registry Authors. All Rights Reserved.
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  
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
 * 'transfer-disputed-won', 'transfer-disputed-lost', 'transfer-disputed-nondecision',
 * 'agp-exemption-requests', 'agp-exemptions-granted' and 'agp-exempted-domains' and
 * the year and month for the ICANN report.
 */
@Entity
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
public class IcannTldReportingFields extends ImmutableObject
    implements Buildable, Comparable<IcannTldReportingFields> {

  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();

  /**
   * Top Level Domain Name
   */
  @Id
  @Index
  private String tld;

  /**
   * TransferDisputedWonCount for IcannTldReportingFields
   */
  private int transferDisputedWonCount;

  /**
   * TransferDisputedLostCount for IcannTldReportingFields
   */
  private int transferDisputedLostCount;

  /**
   * TransferDisputedNondecisionCount for IcannTldReportingFields
   */
  private int transferDisputedNondecisionCount;

  /**
   * AgpExemptionRequestsCount for IcannTldReportingFields
   */
  private int agpExemptionRequestsCount;

  /**
   * AgpExemptedDomainsCount for IcannTldReportingFields
   */
  private int agpExemptionsGrantedCount;

  /**
   * AgpExemptedDomainsCount for IcannTldReportingFields
   */
  private int agpExemptedDomainsCount;

  /**
   * Year and Month of IcannTldReportingFields
   */
  @Index
  private YearMonth yearMonth;

  public String getTld() {
    return tld;
  }

  public int getTransferDisputedWonCount() {
    return transferDisputedWonCount;
  }

  public int getTransferDisputedLostCount() {
    return transferDisputedLostCount;
  }

  public int getTransferDisputedNondecisionCount() {
    return transferDisputedNondecisionCount;
  }

  public int getAgpExemptionRequestsCount() {
    return agpExemptionRequestsCount;
  }

  public int getAgpExemptionsGrantedCount() {
    return agpExemptionsGrantedCount;
  }

  public int getAgpExemptedDomainsCount() {
    return agpExemptedDomainsCount;
  }

  public YearMonth getYearMonth() {
    return yearMonth;
  }

  @Override
  public IcannTldReportingFields.Builder asBuilder() {
    return new IcannTldReportingFields.Builder(clone(this));
  }

  @Override
  public int compareTo(IcannTldReportingFields o) {
    return o.getTld().compareTo(this.getTld());
  }

  public static class Builder extends Buildable.Builder<IcannTldReportingFields> {
    public Builder() {
    }

    public Builder(IcannTldReportingFields instance) {
      super(instance);
    }

    public IcannTldReportingFields.Builder setTld(String tld) {
      getInstance().tld = tld;
      return this;
    }

    public IcannTldReportingFields.Builder setTransferDisputedWonCount(Integer transferDisputedWonCount) {
      getInstance().transferDisputedWonCount = transferDisputedWonCount;
      return this;
    }

    public IcannTldReportingFields.Builder setTransferDisputedLostCount(Integer transferDisputedLostCount) {
      getInstance().transferDisputedLostCount = transferDisputedLostCount;
      return this;
    }

    public IcannTldReportingFields.Builder setTransferDisputedNondecisionCount(Integer transferDisputedNondecisionCount) {
      getInstance().transferDisputedNondecisionCount = transferDisputedNondecisionCount;
      return this;
    }

    public IcannTldReportingFields.Builder setAgpExemptionRequestsCount(Integer agpExemptionRequestsCount) {
      getInstance().agpExemptionRequestsCount = agpExemptionRequestsCount;
      return this;
    }

    public IcannTldReportingFields.Builder setAgpExemptionsGrantedCount(Integer agpExemptionsGrantedCount) {
      getInstance().agpExemptionsGrantedCount = agpExemptionsGrantedCount;
      return this;
    }

    public IcannTldReportingFields.Builder setAgpExemptedDomainsCount(Integer agpExemptedDomainsCount) {
      getInstance().agpExemptedDomainsCount = agpExemptedDomainsCount;
      return this;
    }

    public IcannTldReportingFields.Builder setYearMonth(YearMonth yearMonth) {
      getInstance().yearMonth = yearMonth;
      return this;
    }

    public IcannTldReportingFields build() {
      final IcannTldReportingFields instance = getInstance();

      checkNotNull(instance.getTld(), "Tld cannot be null.");
      checkArgument(instance.getTransferDisputedWonCount() >= 0, "Transfer-Disputed-Won count cannot be negative.");
      checkArgument(instance.getTransferDisputedLostCount() >= 0, "Transfer-Disputed-Lost count cannot be negative.");
      checkArgument(instance.getTransferDisputedNondecisionCount() >= 0, "Transfer-Disputed-Nondecision count cannot be negative.");
      checkArgument(instance.getAgpExemptionRequestsCount() >= 0, "Agp-Exemption-Requests count cannot be negative.");
      checkArgument(instance.getAgpExemptionsGrantedCount() >= 0, "Agp-Exemptions-Granted count cannot be negative.");
      checkArgument(instance.getAgpExemptedDomainsCount() >= 0, "Agp-Exempted-Domains count cannot be negative.");
      checkNotNull(instance.getYearMonth(), "Year-month cannot be null.");
      checkArgument(instance.getYearMonth().size() != 6, "Year-month cannot be less than 6 digits.");

      return super.build();
    }
  }
}
