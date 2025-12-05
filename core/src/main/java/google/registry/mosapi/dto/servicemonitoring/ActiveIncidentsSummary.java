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

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * A summary of active incidents for a specific service that is down.
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification, Section
 *     5.1</a>
 */
public final class ActiveIncidentsSummary {
  /**
   * The name of the service being monitored (e.g., "DNS", "RDDS").
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  @Expose
  @SerializedName("service")
  private final String service;

  /**
   * A JSON number that contains the current percentage of the Emergency Threshold of the Service.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  @Expose
  @SerializedName("emergencyThreshold")
  private final double emergencyThreshold;

  /**
   * A JSON array that contains "incident" objects representing active or resolved incidents for
   * this service.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  @Expose
  @SerializedName("incidents")
  private final List<IncidentSummary> incidents;

  public ActiveIncidentsSummary(
      String service, double emergencyThreshold, List<IncidentSummary> incidents) {
    this.service = service;
    this.emergencyThreshold = emergencyThreshold;
    this.incidents = incidents;
  }

  public String getService() {
    return service;
  }

  public double getEmergencyThreshold() {
    return emergencyThreshold;
  }

  public List<IncidentSummary> getIncidents() {
    return incidents;
  }
}
