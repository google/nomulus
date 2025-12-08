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

/**
 * Represents the downtime information for a specific service.
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification, Section
 *     5.3</a>
 */
public final class ServiceDowntime {
  private int version;
  private long lastUpdateApiDatabase;

  // A JSON number that contains the number of minutes of downtime of the Service during a rolling
  // week period.
  @Expose private int downtime;
  @Expose private Boolean disabledMonitoring;

  public ServiceDowntime(
      int version, long lastUpdateApiDatabase, int downtime, Boolean disabledMonitoring) {
    this.version = version;
    this.lastUpdateApiDatabase = lastUpdateApiDatabase;
    this.downtime = downtime;
    this.disabledMonitoring = disabledMonitoring;
  }

  public int getVersion() {
    return version;
  }

  public long getLastUpdateApiDatabase() {
    return lastUpdateApiDatabase;
  }

  public int getDowntime() {
    return downtime;
  }

  public Boolean getDisabledMonitoring() {
    return disabledMonitoring;
  }
}
