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

package google.registry.mosapi.dto.servicemonitoring;

import java.util.List;

/** Represents the status of a single monitored service. */
public final class ServiceStatus {
  /**
   * A JSON string that contains the status of the Service as seen from the monitoring system.
   * Possible values include "Up", "Down", "Disabled", "UP-inconclusive-no-data", etc.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  private String status;

  //  A JSON number that contains the current percentage of the Emergency Threshold
  //  of the Service. A value of "0" specifies that there are no Incidents
  //  affecting the threshold.
  private double emergencyThreshold;
  private List<IncidentSummary> incidents;

  public ServiceStatus(String status, double emergencyThreshold, List<IncidentSummary> incidents) {
    this.status = status;
    this.emergencyThreshold = emergencyThreshold;
    this.incidents = incidents;
  }

  public String getStatus() {
    return status;
  }

  public double getEmergencyThreshold() {
    return emergencyThreshold;
  }

  public List<IncidentSummary> getIncidents() {
    return incidents;
  }
}
