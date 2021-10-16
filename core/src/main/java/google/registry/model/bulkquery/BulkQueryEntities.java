// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.bulkquery;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.GracePeriod.GracePeriodHistory;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.secdns.DomainDsDataHistory;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTransactionManager;

/**
 * Utilities for managing an alternative JPA entity model optimized for bulk loading multi-level
 * entities such as {@link DomainBase} and {@link DomainHistory}.
 *
 * <p>In a bulk query for a multi-level JPA entity type, the JPA framework only generates a bulk
 * query (SELECT * FROM table) for the base table. Then, for each row in the base table, additional
 * queries are issued to load associated rows in child tables. This can be very slow when an entity
 * type has multiple child tables.
 *
 * <p>We have defined an alternative entity model for {@code DomainBase} and {@code DomainHistory},
 * where the base table as well as the child tables are mapped to single-level entity types. The
 * idea is to load each of these types using a bulk query, and assemble them into the target type in
 * memory in a pipeline. The main use case is Datastore-Cloud SQL validation during the Registry
 * database migration, where we will need the full database snapshots frequently.
 */
public class BulkQueryEntities {
  /**
   * The JPA entity classes in persistence.xml to replace when creating the {@link
   * JpaTransactionManager} for bulk query.
   */
  public static final ImmutableMap<String, String> JPA_ENTITIES_REPLACEMENTS =
      ImmutableMap.of(
          DomainBase.class.getCanonicalName(),
          DomainBaseLite.class.getCanonicalName(),
          DomainHistory.class.getCanonicalName(),
          DomainHistoryLite.class.getCanonicalName());

  /* The JPA entity classes to be added to the {@link
   * JpaTransactionManager} for bulk query.*/
  public static final ImmutableList<String> JPA_ENTITIES_NEW =
      ImmutableList.of(
          DomainHost.class.getCanonicalName(), DomainHistoryHost.class.getCanonicalName());

  public static DomainBase assembleDomainBase(
      DomainBaseLite domainBaseLite,
      ImmutableSet<GracePeriod> gracePeriods,
      ImmutableSet<DelegationSignerData> delegationSignerData,
      ImmutableSet<VKey<HostResource>> nsHosts) {
    DomainBase.Builder builder = new DomainBase.Builder();
    builder.copyFrom(domainBaseLite);
    builder.setGracePeriods(gracePeriods);
    builder.setDsData(delegationSignerData);
    builder.setNameservers(nsHosts);
    return builder.build();
  }

  public static DomainHistory assembleDomainHistory(
      DomainHistoryLite domainHistoryLite,
      ImmutableSet<DomainDsDataHistory> dsDataHistories,
      ImmutableSet<VKey<HostResource>> domainHistoryHosts,
      ImmutableSet<GracePeriodHistory> gracePeriodHistories,
      ImmutableSet<DomainTransactionRecord> transactionRecords) {
    DomainHistory.Builder builder = new DomainHistory.Builder();
    builder.copyFrom(domainHistoryLite);
    DomainContent rawDomainContent = domainHistoryLite.domainContent;
    if (rawDomainContent != null) {
      DomainContent newDomainContent =
          domainHistoryLite
              .domainContent
              .asBuilder()
              .setNameservers(domainHistoryHosts)
              .setGracePeriods(
                  gracePeriodHistories.stream()
                      .map(GracePeriod::createFromHistory)
                      .collect(toImmutableSet()))
              .setDsData(
                  dsDataHistories.stream()
                      .map(DelegationSignerData::create)
                      .collect(toImmutableSet()))
              .build();
      builder.setDomain(newDomainContent);
    }
    return builder.buildAndAssemble(
        dsDataHistories, domainHistoryHosts, gracePeriodHistories, transactionRecords);
  }
}
