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
import google.registry.mosapi.dto.servicemonitoring.AlarmResponse;
import google.registry.mosapi.dto.servicemonitoring.AlarmStatus;
import google.registry.mosapi.dto.servicemonitoring.ServiceAlarm;
import google.registry.mosapi.exception.MosApiException;
import jakarta.inject.Inject;
import java.util.List;

/** Service to check the alarm status for all configured MoSAPI entities and services. */
public class MosApiAlarmService {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final ServiceMonitoringClient serviceMonitoringClient;
  private final List<String> tlds;
  private final List<String> services;

  @Inject
  public MosApiAlarmService(
      ServiceMonitoringClient serviceMonitoringClient,
      @Config("mosapiTlds") List<String> tlds,
      @Config("mosapiServices") List<String> services) {
    this.serviceMonitoringClient = serviceMonitoringClient;
    this.tlds = tlds;
    this.services = services;
  }

  public AlarmResponse checkAllAlarms() {
    ImmutableList.Builder<AlarmStatus> statuses = new ImmutableList.Builder<>();
    for (String tld : tlds) {
      for (String service : services) {
        try {
          // Call the client to get the full alarm object
          ServiceAlarm alarm = serviceMonitoringClient.serviceAlarmed(tld, service);
          // Extract the status string to build the final response object
          statuses.add(new AlarmStatus(tld, service, alarm.getAlarmed(), null));
        } catch (MosApiException e) {
          logger.atWarning().withCause(e).log(
              "Failed to get alarm status for tld %s and service %s.", tld, service);
          statuses.add(new AlarmStatus(tld, service, "ERROR", e.getMessage()));
        }
      }
    }
    return new AlarmResponse(statuses.build());
  }
}
