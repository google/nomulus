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
import java.util.List;

/** Represents the aggregated alarm status for all monitored entities. */
public final class AlarmResponse {
  /**
   * A list of alarm statuses for individual services.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.2</a>
   */
  @Expose private final List<AlarmStatus> alarmStatuses;

  public AlarmResponse(List<AlarmStatus> alarmStatuses) {
    this.alarmStatuses = alarmStatuses;
  }

  public List<AlarmStatus> getAlarmStatuses() {
    return alarmStatuses;
  }
}
