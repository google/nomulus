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

package google.registry.model.transfer;

import com.google.common.base.CaseFormat;
import jakarta.xml.bind.annotation.XmlEnumValue;

/** Represents the EPP transfer status as defined in RFC 5730. */
public enum TransferStatus {
  @XmlEnumValue("clientApproved")
  CLIENT_APPROVED("Transfer approved."),

  @XmlEnumValue("clientCancelled")
  CLIENT_CANCELLED("Transfer cancelled."),

  @XmlEnumValue("clientRejected")
  CLIENT_REJECTED("Transfer rejected."),

  @XmlEnumValue("pending")
  PENDING("Transfer requested."),

  @XmlEnumValue("serverApproved")
  SERVER_APPROVED("Transfer approved."),

  @XmlEnumValue("serverCancelled")
  SERVER_CANCELLED("Transfer cancelled.");

  private final String message;

  TransferStatus(String message) {
    this.message = message;
  }

  public String getMessage() {
    return message;
  }

  public String getXmlName() {
    return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, toString());
  }

  public boolean isApproved() {
    return this.equals(CLIENT_APPROVED) || this.equals(SERVER_APPROVED);
  }

  public boolean isDenied() {
    return this.equals(CLIENT_CANCELLED)
        || this.equals(CLIENT_REJECTED)
        || this.equals(SERVER_CANCELLED);
  }
}
