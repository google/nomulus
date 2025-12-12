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
package google.registry.mosapi.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A curated summary of the service state for a TLD.
 *
 * <p>This class aggregates the high-level status of a TLD and details of any active incidents
 * affecting specific services (like DNS or RDDS), based on the data structures defined in the
 * MoSAPI specification.
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification, Section
 *     5.1</a>
 */
public final class ServiceStateSummary {
  @Expose
  @SerializedName("tld")
  private final String tld;

  // The overall status of the TLD (e.g. "Up", "Down", "UP-inconclusive")
  @Expose
  @SerializedName("overallStatus")
  private final String overallStatus;

  /*
    A list of summaries for services that currently have active incidents. May be null or empty if
    the status is "up"
  */
  @Expose
  @SerializedName("activeIncidents")
  @Nullable
  private final List<ServiceStatus> activeIncidents;

  public ServiceStateSummary(
      String tld, String overallStatus, @Nullable List<ServiceStatus> activeIncidents) {
    this.tld = tld;
    this.overallStatus = overallStatus;
    this.activeIncidents = activeIncidents;
  }

  public String getTld() {
    return tld;
  }

  public String getOverallStatus() {
    return overallStatus;
  }

  @Nullable
  public List<ServiceStatus> getActiveIncidents() {
    return activeIncidents;
  }
}
