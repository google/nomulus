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

/** Represents the alarm status for a specific service. */
public final class ServiceAlarm {
  @Expose private int version;
  @Expose private long lastUpdateApiDatabase;

  // A JSON string that contains one of the following values: "Yes", "No", or "Disabled"
  @Expose private String alarmed;

  public ServiceAlarm(int version, long lastUpdateApiDatabase, String alarmed) {
    this.version = version;
    this.lastUpdateApiDatabase = lastUpdateApiDatabase;
    this.alarmed = alarmed;
  }

  public int getVersion() {
    return version;
  }

  public long getLastUpdateApiDatabase() {
    return lastUpdateApiDatabase;
  }

  public String getAlarmed() {
    return alarmed;
  }
}
