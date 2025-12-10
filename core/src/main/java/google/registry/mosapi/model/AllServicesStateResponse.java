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

/**
 * A wrapper response containing the state summaries of all monitored services.
 *
 * <p>This corresponds to the collection of service statuses returned when monitoring the state of a
 * TLD
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification, Section
 *     5.1</a>
 */
public final class AllServicesStateResponse {

  // A list of state summaries for each monitored service (e.g. DNS, RDDS, etc.)
  @Expose
  @SerializedName("serviceStates")
  private final List<ServiceStateSummary> serviceStates;

  public AllServicesStateResponse(List<ServiceStateSummary> serviceStates) {
    this.serviceStates = serviceStates;
  }

  public List<ServiceStateSummary> getServiceStates() {
    return serviceStates;
  }
}
