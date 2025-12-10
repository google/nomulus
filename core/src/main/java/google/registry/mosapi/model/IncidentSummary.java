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
import javax.annotation.Nullable;

/**
 * A summary of a service incident.
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification, Section
 *     5.1</a>
 */
public final class IncidentSummary {
  @Expose
  @SerializedName("incidentID")
  private String incidentID;

  @Expose
  @SerializedName("startTime")
  private long startTime;

  @Expose
  @SerializedName("falsePositive")
  private boolean falsePositive;

  @Expose
  @SerializedName("state")
  private String state;

  @Expose
  @SerializedName("endTime")
  @Nullable
  private Long endTime;

  public IncidentSummary(
      String incidentID, long startTime, boolean falsePositive, String state, Long endTime) {
    this.incidentID = incidentID;
    this.startTime = startTime;
    this.falsePositive = falsePositive;
    this.state = state;
    this.endTime = endTime;
  }

  public String getIncidentID() {
    return incidentID;
  }

  public long getStartTime() {
    return startTime;
  }

  public boolean isFalsePositive() {
    return falsePositive;
  }

  public String getState() {
    return state;
  }

  public Long getEndTime() {
    return endTime;
  }
}
