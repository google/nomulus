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

package google.registry.mosapi.services;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.mosapi.client.ServiceMonitoringClient;
import google.registry.mosapi.dto.servicemonitoring.AllTldsDowntime;
import google.registry.mosapi.dto.servicemonitoring.ServiceDowntime;
import google.registry.mosapi.dto.servicemonitoring.TldServicesDowntime;
import google.registry.mosapi.exception.MosApiException;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Service to fetch downtime reports from the MoSAPI. */
public class MosApiDowntimeService {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final ServiceMonitoringClient serviceMonitoringClient;
  private final List<String> tlds;
  private final List<String> services;

  @Inject
  public MosApiDowntimeService(
      ServiceMonitoringClient serviceMonitoringClient,
      @Config("mosapiTlds") List<String> tlds,
      @Config("mosapiServices") List<String> services) {
    this.serviceMonitoringClient = serviceMonitoringClient;
    this.tlds = tlds;
    this.services = services;
  }

  /** Fetches the downtime for all configured services for a given TLD. */
  public TldServicesDowntime getDowntimeForTld(String tld) {
    Map<String, ServiceDowntime> serviceDowntimes = new HashMap<>();
    for (String service : services) {
      try {
        serviceDowntimes.put(service, serviceMonitoringClient.getDowntime(tld, service));
      } catch (MosApiException e) {
        logger.atWarning().withCause(e).log(
            "Failed to get service downtime for TLD %s and service %s.", tld, service);
      }
    }
    return new TldServicesDowntime(tld, serviceDowntimes);
  }

  /** Fetches the downtime for all configured services across all configured TLDs. */
  public AllTldsDowntime getDowntimeForAllTlds() {
    ImmutableList.Builder<TldServicesDowntime> allDowntimes = new ImmutableList.Builder<>();
    for (String tld : tlds) {
      allDowntimes.add(getDowntimeForTld(tld));
    }
    return new AllTldsDowntime(allDowntimes.build());
  }
}
