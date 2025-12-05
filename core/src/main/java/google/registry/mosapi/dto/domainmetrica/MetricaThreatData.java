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

package google.registry.mosapi.dto.domainmetrica;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import java.util.List;

/** Represents one entry in the domainListData array of a {@link MetricaReport}. */
public final class MetricaThreatData {
  /**
   * A JSON string describing the type of DNS abuse. Known types include: spam, phishing, botnetCc
   * and malware.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.1</a>
   */
  @Expose
  @SerializedName("threatType")
  private final String threatType;

  /**
   * A JSON number containing the number of domains for the type of abuse. If this value is set to
   * "-1" the specific threat Type is disabled and is not currently being monitored.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.1</a>
   */
  @Expose
  @SerializedName("count")
  private final int count;

  /**
   * A JSON array of JSON strings containing the domain names presenting abuse. This list may not
   * include all the domains identified in the "count" element.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.1</a>
   */
  @Expose
  @SerializedName("domains")
  private final List<String> domains;

  public MetricaThreatData(String threatType, int count, List<String> domains) {
    this.threatType = threatType;
    this.count = count;
    this.domains = domains;
  }

  public String getThreatType() {
    return threatType;
  }

  public int getCount() {
    return count;
  }

  public List<String> getDomains() {
    return domains;
  }
}
