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

package google.registry.mosapi;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.mosapi.model.AllServicesStateResponse;
import google.registry.mosapi.model.ServiceStateSummary;
import google.registry.mosapi.model.ServiceStatus;
import google.registry.mosapi.model.TldServiceState;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** A service that provides business logic for interacting with MoSAPI Service State. */
public class MosApiStateService {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final ServiceMonitoringClient serviceMonitoringClient;
  private final ExecutorService tldExecutor;

  private final ImmutableSet<String> tlds;

  private static final String DOWN_STATUS = "Down";
  private static final String FETCH_ERROR_STATUS = "ERROR";

  @Inject
  public MosApiStateService(
      ServiceMonitoringClient serviceMonitoringClient,
      @Config("mosapiTlds") ImmutableSet<String> tlds,
      @Named("mosapiTldExecutor") ExecutorService tldExecutor) {
    this.serviceMonitoringClient = serviceMonitoringClient;
    this.tlds = tlds;
    this.tldExecutor = tldExecutor;
  }

  /** Fetches and transforms the service state for a given TLD into a summary. */
  public ServiceStateSummary getServiceStateSummary(String tld) throws MosApiException {
    TldServiceState rawState = serviceMonitoringClient.getTldServiceState(tld);
    // TODO(b/467541269): Expose MosApi Service Monitoring response to Cloud monitoring
    return transformToSummary(rawState);
  }

  /** Fetches and transforms the service state for all configured TLDs. */
  public AllServicesStateResponse getAllServiceStateSummaries() {
    ImmutableList<CompletableFuture<ServiceStateSummary>> futures =
        tlds.stream()
            .map(
                tld ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            return getServiceStateSummary(tld);
                          } catch (MosApiException e) {
                            logger.atWarning().withCause(e).log(
                                "Failed to get service state for TLD %s.", tld);
                            // we don't want to throw exception if fetch failed
                            return new ServiceStateSummary(tld, FETCH_ERROR_STATUS, null);
                          }
                        },
                        tldExecutor))
            .collect(ImmutableList.toImmutableList());

    ImmutableList<ServiceStateSummary> summaries =
        futures.stream()
            .map(CompletableFuture::join) // Waits for all tasks to complete
            .collect(toImmutableList());

    return new AllServicesStateResponse(summaries);
  }

  private ServiceStateSummary transformToSummary(TldServiceState rawState) {
    List<ServiceStatus> activeIncidents = null;
    if (DOWN_STATUS.equalsIgnoreCase(rawState.getStatus())) {
      activeIncidents =
          rawState.getServiceStatuses().entrySet().stream()
              .filter(
                  entry -> {
                    ServiceStatus serviceStatus = entry.getValue();
                    return serviceStatus.getIncidents() != null
                        && !serviceStatus.getIncidents().isEmpty();
                  })
              .map(
                  entry ->
                      new ServiceStatus(
                          // key is the service name
                          entry.getKey(),
                          entry.getValue().getEmergencyThreshold(),
                          entry.getValue().getIncidents()))
              .collect(toImmutableList());
    }
    return new ServiceStateSummary(rawState.getTld(), rawState.getStatus(), activeIncidents);
  }
}
