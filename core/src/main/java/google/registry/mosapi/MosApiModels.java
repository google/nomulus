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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Data models for ICANN MoSAPI. */
public final class MosApiModels {

  private MosApiModels() {}

  /**
   * A wrapper response containing the state summaries of all monitored services.
   *
   * <p>This corresponds to the collection of service statuses returned when monitoring the state of
   * a TLD
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  public record AllServicesStateResponse(
      // A list of state summaries for each monitored service (e.g. DNS, RDDS, etc.)
      @Expose List<ServiceStateSummary> serviceStates) {

    public AllServicesStateResponse {
      serviceStates = (serviceStates == null) ? ImmutableList.of() : serviceStates;
    }
  }

  /**
   * A summary of a service incident.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  public record IncidentSummary(
      @Expose String incidentID,
      @Expose long startTime,
      @Expose boolean falsePositive,
      @Expose String state,
      @Expose @Nullable Long endTime) {}

  /**
   * A curated summary of the service state for a TLD.
   *
   * <p>This class aggregates the high-level status of a TLD and details of any active incidents
   * affecting specific services (like DNS or RDDS), based on the data structures defined in the
   * MoSAPI specification.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  public record ServiceStateSummary(
      @Expose String tld,
      @Expose String overallStatus,
      @Expose List<ServiceStatus> activeIncidents) {

    public ServiceStateSummary {
      activeIncidents = activeIncidents == null ? ImmutableList.of() : activeIncidents;
    }
  }

  /** Represents the status of a single monitored service. */
  public record ServiceStatus(
      /**
       * A JSON string that contains the status of the Service as seen from the monitoring system.
       * Possible values include "Up", "Down", "Disabled", "UP-inconclusive-no-data", etc.
       */
      @Expose String status,

      //  A JSON number that contains the current percentage of the Emergency Threshold
      //  of the Service. A value of "0" specifies that there are no Incidents
      //  affecting the threshold.
      @Expose double emergencyThreshold,
      @Expose List<IncidentSummary> incidents) {

    public ServiceStatus {
      incidents = incidents == null ? ImmutableList.of() : incidents;
    }
  }

  /**
   * Represents the overall health of all monitored services for a TLD.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  public record TldServiceState(
      @Expose String tld,
      long lastUpdateApiDatabase,

      // A JSON string that contains the status of the TLD as seen from the monitoring system
      @Expose String status,

      // A JSON object containing detailed information for each potential monitored service (i.e.,
      // DNS,
      //  RDDS, EPP, DNSSEC, RDAP).
      @Expose @SerializedName("testedServices") Map<String, ServiceStatus> serviceStatuses) {

    public TldServiceState {
      serviceStatuses = (serviceStatuses == null) ? ImmutableMap.of() : serviceStatuses;
    }
  }
}
