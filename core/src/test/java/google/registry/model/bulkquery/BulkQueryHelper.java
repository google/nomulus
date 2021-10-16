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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.GracePeriod.GracePeriodHistory;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.secdns.DomainDsDataHistory;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.persistence.VKey;

/**
 * Helpers for bulk-loading {@link google.registry.model.domain.DomainBase} and {@link
 * google.registry.model.domain.DomainHistory} entities in <em>tests</em>.
 */
public class BulkQueryHelper {

  static DomainBase loadAndAssembleDomainBase(String domainRepoId) {
    return jpaTm()
        .transact(
            () ->
                BulkQueryEntities.assembleDomainBase(
                    jpaTm().loadByKey(DomainBaseLite.createVKey(domainRepoId)),
                    jpaTm()
                        .loadAllOfStream(GracePeriod.class)
                        .filter(gracePeriod -> gracePeriod.getDomainRepoId().equals(domainRepoId))
                        .collect(toImmutableSet()),
                    jpaTm()
                        .loadAllOfStream(DelegationSignerData.class)
                        .filter(dsData -> dsData.getDomainRepoId().equals(domainRepoId))
                        .collect(toImmutableSet()),
                    jpaTm()
                        .loadAllOfStream(DomainHost.class)
                        .filter(domainHost -> domainHost.getDomainRepoId().equals(domainRepoId))
                        .map(DomainHost::getHostVKey)
                        .collect(toImmutableSet())));
  }

  static DomainHistory loadAndAssembleDomainHistory(DomainHistoryId domainHistoryId) {
    return jpaTm()
        .transact(
            () ->
                BulkQueryEntities.assembleDomainHistory(
                    jpaTm().loadByKey(VKey.createSql(DomainHistoryLite.class, domainHistoryId)),
                    jpaTm()
                        .loadAllOfStream(DomainDsDataHistory.class)
                        .filter(
                            domainDsDataHistory ->
                                domainDsDataHistory.getDomainHistoryId().equals(domainHistoryId))
                        .collect(toImmutableSet()),
                    jpaTm()
                        .loadAllOfStream(DomainHistoryHost.class)
                        .filter(
                            domainHistoryHost ->
                                domainHistoryHost.getDomainHistoryId().equals(domainHistoryId))
                        .map(DomainHistoryHost::getHostVKey)
                        .collect(toImmutableSet()),
                    jpaTm()
                        .loadAllOfStream(GracePeriodHistory.class)
                        .filter(
                            gracePeriodHistory ->
                                gracePeriodHistory.getDomainHistoryId().equals(domainHistoryId))
                        .collect(toImmutableSet()),
                    jpaTm()
                        .loadAllOfStream(DomainTransactionRecord.class)
                        .filter(x -> true)
                        .collect(toImmutableSet())));
  }
}
