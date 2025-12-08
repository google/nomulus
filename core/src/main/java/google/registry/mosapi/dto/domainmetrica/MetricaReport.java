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

/**
 * Represents a full Domain METRICA report.
 *
 * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification, Section
 *     9.1</a>
 */
public final class MetricaReport {
  private final int version;

  @Expose
  @SerializedName("tld")
  private final String tld;

  /**
   * A JSON string that contains the date of the report in the format YYYY-MM-DD.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.1</a>
   */
  @Expose
  @SerializedName("domainListDate")
  private final String domainListDate;

  /**
   * A JSON number that includes the total number of unique abuse domains detected for the
   * particular date.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.1</a>
   */
  @Expose
  @SerializedName("uniqueAbuseDomains")
  private final int uniqueAbuseDomains;

  /**
   * An array of JSON objects describing the type of DNS abuse (spam, phishing, botnetCc, malware)
   * and the count of domains.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 9.1</a>
   */
  @Expose
  @SerializedName("domainListData")
  private final List<MetricaThreatData> threats;

  public MetricaReport(
      int version,
      String tld,
      String domainListDate,
      int uniqueAbuseDomains,
      List<MetricaThreatData> threats) {
    this.version = version;
    this.tld = tld;
    this.domainListDate = domainListDate;
    this.uniqueAbuseDomains = uniqueAbuseDomains;
    this.threats = threats;
  }

  public int getVersion() {
    return version;
  }

  public String getTld() {
    return tld;
  }

  public String getDomainListDate() {
    return domainListDate;
  }

  public int getUniqueAbuseDomains() {
    return uniqueAbuseDomains;
  }

  public List<MetricaThreatData> getThreats() {
    return threats;
  }
}
