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

package google.registry.beam.spec11;

import com.google.common.annotations.VisibleForTesting;
import java.io.Serializable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A record representing a threat match response from the {@code SafeBrowsing API}.
 *
 * @param threatType What kind of threat this is (malware, phishing, etc.).
 * @param domainName The fully qualified domain name of the matched threat.
 */
public record ThreatMatch(String threatType, String domainName) implements Serializable {

  private static final String THREAT_TYPE_FIELD = "threatType";
  private static final String DOMAIN_NAME_FIELD = "domainName";
  private static final String OUTDATED_NAME_FIELD = "fullyQualifiedDomainName";

  @VisibleForTesting
  static ThreatMatch create(String threatType, String domainName) {
    return new ThreatMatch(threatType, domainName);
  }

  /** Returns a {@link JSONObject} representing a subset of this object's data. */
  JSONObject toJSON() throws JSONException {
    return new JSONObject()
        .put(THREAT_TYPE_FIELD, threatType())
        .put(DOMAIN_NAME_FIELD, domainName());
  }

  /** Parses a {@link JSONObject} and returns an equivalent {@link ThreatMatch}. */
  public static ThreatMatch fromJSON(JSONObject threatMatch) throws JSONException {
    // TODO: delete OUTDATED_NAME_FIELD once we no longer process reports saved with
    // fullyQualifiedDomainName in them, likely 2023
    return new ThreatMatch(
        threatMatch.getString(THREAT_TYPE_FIELD),
        threatMatch.has(OUTDATED_NAME_FIELD)
            ? threatMatch.getString(OUTDATED_NAME_FIELD)
            : threatMatch.getString(DOMAIN_NAME_FIELD));
  }
}
