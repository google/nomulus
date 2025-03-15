// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.icann;

import static google.registry.reporting.icann.IcannReportingModule.ICANN_REPORTING_DATA_SET;
import static google.registry.util.TypeUtils.getClassFromString;
import static google.registry.util.TypeUtils.instantiate;

import dagger.MembersInjector;
import dagger.Module;
import dagger.Provides;
import google.registry.bigquery.BigqueryConnection;
import google.registry.config.RegistryConfig.Config;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.joda.time.YearMonth;

/**
 * Methods for preparing and querying DNS statistics.
 *
 * <p>DNS systems may have different ways of providing this information, so it's useful to
 * modularize this, by providing defining the `registryPolicy.dnsCountQueryCoordinatorClass` in your
 * config file.
 *
 * <p>Due to limitations of {@link MembersInjector}, any injectable field needs to be declared in
 * the base class, even if it is only used in a derived class.
 */
public abstract class DnsCountQueryCoordinator {

  @Inject BigqueryConnection bigquery;

  @Inject
  @Config("projectId")
  String projectId;

  @Inject
  @Named(ICANN_REPORTING_DATA_SET)
  String icannReportingDataSet;

  /** Creates the string used to query bigtable for DNS count information. */
  abstract String createQuery();

  /**
   * Do any necessary preparation for the DNS query.
   *
   * <p>This potentially throws {@link InterruptedException} because some implementations use
   * interruptible futures to prepare the query (and the correct thing to do with such exceptions is
   * to handle them correctly or propagate them as-is, no {@link RuntimeException} wrapping).
   */
  abstract void prepareForQuery(YearMonth yearMonth) throws InterruptedException;

  @Module
  public static class DnsCountQueryCoordinatorModule {
    @Provides
    static DnsCountQueryCoordinator provideDnsCountQueryCoordinator(
        MembersInjector<DnsCountQueryCoordinator> injector,
        @Config("dnsCountQueryCoordinatorClass") String customClass) {
      DnsCountQueryCoordinator coordinator =
          instantiate(getClassFromString(customClass, DnsCountQueryCoordinator.class));
      injector.injectMembers(coordinator);
      return coordinator;
    }
  }
}
