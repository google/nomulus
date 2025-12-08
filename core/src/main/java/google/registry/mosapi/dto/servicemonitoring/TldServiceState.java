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
import java.util.Map;

/**
 * Represents the overall health of all monitored services for a TLD.
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification, Section
 *     5.1</a>
 */
public final class TldServiceState {
  @Expose private final String tld;
  private final long lastUpdateApiDatabase;

  // A JSON string that contains the status of the TLD as seen from the monitoring system
  @Expose private final String status;

  // A JSON object containing detailed information for each potential monitored service (i.e., DNS,
  //  RDDS, EPP, DNSSEC, RDAP).
  @Expose
  @SerializedName("testedServices")
  private final Map<String, ServiceStatus> serviceStatuses;

  public TldServiceState(
      String tld,
      long lastUpdateApiDatabase,
      String status,
      Map<String, ServiceStatus> serviceStatuses) {
    this.tld = tld;
    this.lastUpdateApiDatabase = lastUpdateApiDatabase;
    this.status = status;
    this.serviceStatuses = serviceStatuses;
  }

  public String getTld() {
    return tld;
  }

  public long getLastUpdateApiDatabase() {
    return lastUpdateApiDatabase;
  }

  public String getStatus() {
    return status;
  }

  public Map<String, ServiceStatus> getServiceStatuses() {
    return serviceStatuses;
  }
}
