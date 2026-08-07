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

package google.registry.tools.javascrap;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import google.registry.dns.DnsUtils;
import google.registry.model.EppResource;
import google.registry.model.EppResourceUtils;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.util.Clock;
import google.registry.util.RegistryEnvironment;
import google.registry.util.SystemClock;
import org.joda.time.DateTime;

import java.util.Locale;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

public class DomainCleanupHosts {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  @SuppressWarnings("unused")
  public static void main(String[] args) throws Exception {
      checkArgument(args.length == 2, "Expecting two args: `env` and `domainName`");
      String envName = args[0];
      String domainName = args[1];
      RegistryEnvironment.valueOf(envName.toUpperCase(Locale.ROOT)).setup();

      Clock clock = new SystemClock();
      DateTime now = clock.nowUtc();

      Domain domain = loadAndVerifyExistence(Domain.class, domainName, now);
      tm().transact(() -> {
          ImmutableSet<VKey<Host>> validHosts =
                  domain.getNsHosts().stream()
                          .map(tm()::loadByKey)
                          .filter(e -> e.getDeletionTime().isAfterNow())
                          .map(Host::createVKey)
                          .collect(toImmutableSet());

      logger.atInfo().log("NsHosts: %s\nInvalid Hosts: %s", domain.getNsHosts(), Sets.difference(domain.getNsHosts(), validHosts));

      long revision_id = (Long) tm().getEntityManager().createNativeQuery(
              "SELECT MAX(history_revision_id) from \"DomainHistory\" " +
              "WHERE domain_repo_id = '" + domain.getRepoId() + "'")
              .getSingleResultOrNull();
      logger.atInfo().log("Latest history id is %s", revision_id);

          DomainHistory domainHistory = tm().loadByKey(VKey.create(DomainHistory.class, new HistoryEntry.HistoryEntryId(domain.getRepoId(), revision_id)));
          logger.atInfo().log("DomainHistory nsHosts: %s", domainHistory.getNsHosts());

          // DnsUtils.requestDomainDnsRefresh(domainName);
      });
  }
}
