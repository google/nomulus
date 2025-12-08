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
import javax.annotation.Nullable;

/** Represents the alarm status for a single TLD and service combination. */
public final class AlarmStatus {

  // The TLD being monitored.
  @Expose private final String tld;

  // The service being monitored (e.g. "dns", "rdds").
  @Expose private final String service;

  /**
   * The alarm status (e.g. "Yes", "No", "Disabled").
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.2</a>
   */
  @Expose private final String status;

  /** An optional error message if the status could not be retrieved. */
  @Expose @Nullable private final String errorMessage;

  public AlarmStatus(String tld, String service, String status, @Nullable String errorMessage) {
    this.tld = tld;
    this.service = service;
    this.status = status;
    this.errorMessage = errorMessage;
  }

  public String getTld() {
    return tld;
  }

  public String getService() {
    return service;
  }

  public String getStatus() {
    return status;
  }

  @Nullable
  public String getErrorMessage() {
    return errorMessage;
  }
}
