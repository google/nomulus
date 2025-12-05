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

/** Represents a collection of downtime reports for all services on a given TLD. */
public final class TldServicesDowntime {
  @Expose
  @SerializedName("tld")
  private final String tld;

  @Expose
  @SerializedName("serviceDowntime")
  private final Map<String, ServiceDowntime> serviceDowntime;

  public TldServicesDowntime(String tld, Map<String, ServiceDowntime> serviceDowntime) {
    this.tld = tld;
    this.serviceDowntime = serviceDowntime;
  }

  public String getTld() {
    return tld;
  }

  public Map<String, ServiceDowntime> getServiceDowntime() {
    return serviceDowntime;
  }
}
