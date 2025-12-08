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
 * A wrapper for a list of {@link TldServicesDowntime} objects.
 *
 * <p>This class acts as a container for aggregating downtime information across multiple TLDs or
 * services, relating to the downtime measurements defined in the specification.
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification, Section
 *     5.3</a>
 */
public final class AllTldsDowntime {
  // A list of downtime metrics for monitored TLDs/Services
  @Expose
  @SerializedName("allDowntimes")
  private final List<TldServicesDowntime> allDowntimes;

  public AllTldsDowntime(List<TldServicesDowntime> allDowntimes) {
    this.allDowntimes = allDowntimes;
  }

  public List<TldServicesDowntime> getAllDowntimes() {
    return allDowntimes;
  }
}
