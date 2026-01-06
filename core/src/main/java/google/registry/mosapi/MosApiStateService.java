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
import google.registry.mosapi.MosApiModels.AllServicesStateResponse;
import google.registry.mosapi.MosApiModels.ServiceStateSummary;
import google.registry.mosapi.MosApiModels.ServiceStatus;
import google.registry.mosapi.MosApiModels.TldServiceState;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/** A service that provides business logic for interacting with MoSAPI Service State. */
public class MosApiStateService {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final ServiceMonitoringClient serviceMonitoringClient;
  private final ExecutorService tldExecutor;

  private final ImmutableSet<String> tlds;

  private final MosApiMetrics mosApiMetrics;

  private static final String DOWN_STATUS = "Down";
  private static final String FETCH_ERROR_STATUS = "ERROR";

  @Inject
  public MosApiStateService(
      ServiceMonitoringClient serviceMonitoringClient,
      MosApiMetrics mosApiMetrics,
      @Config("mosapiTlds") ImmutableSet<String> tlds,
      @Named("mosapiTldExecutor") ExecutorService tldExecutor) {
    this.serviceMonitoringClient = serviceMonitoringClient;
    this.mosApiMetrics = mosApiMetrics;
    this.tlds = tlds;
    this.tldExecutor = tldExecutor;
  }

  /** Fetches and transforms the service state for a given TLD into a summary. */
  public ServiceStateSummary getServiceStateSummary(String tld) throws MosApiException {
    TldServiceState rawState = serviceMonitoringClient.getTldServiceState(tld);
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
    ImmutableList<ServiceStatus> activeIncidents = ImmutableList.of();
    if (DOWN_STATUS.equalsIgnoreCase(rawState.status())) {
      activeIncidents =
          rawState.serviceStatuses().entrySet().stream()
              .filter(
                  entry -> {
                    ServiceStatus serviceStatus = entry.getValue();
                    return serviceStatus.incidents() != null
                        && !serviceStatus.incidents().isEmpty();
                  })
              .map(
                  entry ->
                      new ServiceStatus(
                          // key is the service name
                          entry.getKey(),
                          entry.getValue().emergencyThreshold(),
                          entry.getValue().incidents()))
              .collect(toImmutableList());
    }
    return new ServiceStateSummary(rawState.tld(), rawState.status(), activeIncidents);
  }

  /** Triggers monitoring exposure for all configured TLDs. */
  public void triggerMetricsForAllServiceStateSummaries() {
    ImmutableList<CompletableFuture<TldServiceState>> futures =
        tlds.stream()
            .map(
                tld ->
                    CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            return serviceMonitoringClient.getTldServiceState(tld);
                          } catch (MosApiException e) {
                            // Log the error but don't rethrow as RuntimeException
                            logger.atWarning().withCause(e).log(
                                "Failed to fetch state for TLD: %s", tld);
                            return null; // Return null so the stream keeps moving
                          }
                        },
                        tldExecutor))
            .collect(toImmutableList());

    List<TldServiceState> allStates =
        futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    if (!allStates.isEmpty()) {
      try {
        mosApiMetrics.recordStates(allStates);
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Failed to submit MoSAPI metrics batch.");
      }
    } else {
      logger.atWarning().log("No successful TLD states fetched; skipping metrics push.");
    }
  }
}
