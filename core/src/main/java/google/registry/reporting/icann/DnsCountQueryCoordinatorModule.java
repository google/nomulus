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

import dagger.Module;
import dagger.Provides;
import google.registry.bigquery.BigqueryConnection;
import google.registry.config.RegistryConfig.Config;
import javax.inject.Named;

/** Dagger module to provide the DnsCountQueryCoordinator. */
@Module
public class DnsCountQueryCoordinatorModule {

  @Provides
  static DnsCountQueryCoordinator provideDnsCountQueryCoordinator(
      @Config("dnsCountQueryCoordinatorClass") String customClass,
      BigqueryConnection bigquery,
      @Config("projectId") String projectId,
      @Named(ICANN_REPORTING_DATA_SET) String icannReportingDataSet) {
    DnsCountQueryCoordinator.Params params =
        new DnsCountQueryCoordinator.Params(bigquery, projectId, icannReportingDataSet);
    return instantiate(getClassFromString(customClass, DnsCountQueryCoordinator.class), params);
  }
}
