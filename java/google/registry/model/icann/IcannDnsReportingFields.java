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
 * 'dns-udp-queries-received', 'dns-udp-queries-responded', 'dns-tcp-queries-received' and
 * 'dns-tcp-queries-responded' and the year and month for the ICANN report.
 */
@Entity
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
public class IcannDnsReportingFields extends ImmutableObject
    implements Buildable, Comparable<IcannDnsReportingFields> {

  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();

  /**
   * Top Level Domain Name
   */
  @Id
  @Index
  private String tld;

  /**
   * dns-udp-queries-received reporting field of IcannDnsReportingFields
   */
  private String dnsUdpQueriesReceived;

  /**
   * dns-udp-queries-responded reporting field  of IcannDnsReportingFields
   */
  private String dnsUdpQueriesResponded;

  /**
   * dns-tcp-queries-received reporting field of IcannDnsReportingFields
   */
  private String dnsTcpQueriesReceived;

  /**
   * dns-tcp-queries-responded reporting field of IcannDnsReportingFields
   */
  private String dnsTcpQueriesResponded;

  /**
   * DnsUdpQueriesReceivedCount for IcannDnsReportingFields
   */
  private int dnsUdpQueriesReceivedCount;

  /**
   * DnsUdpQueriesRespondedCount for IcannDnsReportingFields
   */
  private int dnsUdpQueriesRespondedCount;

  /**
   * DnsTcpQueriesReceivedCount for IcannDnsReportingFields
   */
  private int dnsTcpQueriesReceivedCount;

  /**
   * DnsTcpQueriesRespondedCount for IcannDnsReportingFields
   */
  private int dnsTcpQueriesRespondedCount;

  /**
   * Year and Month of IcannTldReportingFields
   */
  @Index
  private YearMonth yearMonth;

  public String getTld() {
    return tld;
  }

  public int getDnsUdpQueriesReceivedCount() {
    return dnsUdpQueriesReceivedCount;
  }

  public int getDnsUdpQueriesRespondedCount() {
    return dnsUdpQueriesRespondedCount;
  }

  public int getDnsTcpQueriesReceivedCount() {
    return dnsTcpQueriesReceivedCount;
  }

  public int getDnsTcpQueriesRespondedCount() {
    return dnsTcpQueriesRespondedCount;
  }

  public YearMonth getYearMonth() {
    return yearMonth;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  @Override
  public int compareTo(IcannDnsReportingFields o) {
    return o.getTld().compareTo(this.getTld());
  }

  public static class Builder extends Buildable.Builder<IcannDnsReportingFields> {
    public Builder() {
    }

    public Builder(IcannDnsReportingFields instance) {
      super(instance);
    }

    public Builder setTld(String tld) {
      getInstance().tld = tld;
      return this;
    }

    public Builder setDnsUdpQueriesReceivedCount(int dnsUdpQueriesReceivedCount) {
      getInstance().dnsUdpQueriesReceivedCount = dnsUdpQueriesReceivedCount;
      return this;
    }

    public Builder setDnsUdpQueriesRespondedCount(int dnsUdpQueriesRespondedCount) {
      getInstance().dnsUdpQueriesRespondedCount = dnsUdpQueriesRespondedCount;
      return this;
    }

    public Builder setDnsTcpQueriesReceivedCount(int dnsTcpQueriesReceivedCount) {
      getInstance().dnsTcpQueriesReceivedCount = dnsTcpQueriesReceivedCount;
      return this;
    }

    public Builder setDnsTcpQueriesRespondedCount(int dnsTcpQueriesRespondedCount) {
      getInstance().dnsTcpQueriesRespondedCount = dnsTcpQueriesRespondedCount;
      return this;
    }

    public Builder setYearMonth(YearMonth yearMonth) {
      getInstance().yearMonth = yearMonth;
      return this;
    }

    public IcannDnsReportingFields build() {
      final IcannDnsReportingFields instance = getInstance();

      checkNotNull(instance.getTld(), "Tld cannot be null.");
      checkArgument(instance.getDnsUdpQueriesReceivedCount() >= 0, "Dns-Udp-Queries-Received count cannot be negative.");
      checkArgument(instance.getDnsUdpQueriesRespondedCount() >= 0, "Dns-Udp-Queries-Responded count cannot be negative.");
      checkArgument(instance.getDnsTcpQueriesReceivedCount() >= 0, "Dns-Tcp-Queries-Received count cannot be negative.");
      checkArgument(instance.getDnsTcpQueriesRespondedCount() >= 0, "Dns-Tcp-Queries-Responded count cannot be negative.");
      checkNotNull(instance.getYearMonth(), "Year-month cannot be null.");
      checkArgument(instance.getYearMonth().size() != 6, "Year-month cannot be less than 6 digits.");

      return super.build();
    }
  }
}
