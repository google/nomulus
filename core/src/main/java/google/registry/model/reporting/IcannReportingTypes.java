// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.reporting;

/** Types used for ICANN reporting. */
public final class IcannReportingTypes {
  private IcannReportingTypes() {}

  /**
   * Represents the set of possible ICANN Monthly Registry Functions Activity Report fields.
   *
   * <p>Refer to the <a
   * href="https://newgtlds.icann.org/sites/default/files/agreements/agreement-approved-09jan14-en.htm#_DV_M278">
   * ICANN registry agreement Specification 3 Section 2</a> for details.
   */
  public enum ActivityReportField {
    DOMAIN_CHECK("srs-dom-check"),
    DOMAIN_CREATE("srs-dom-create"),
    DOMAIN_DELETE("srs-dom-delete"),
    DOMAIN_INFO("srs-dom-info"),
    DOMAIN_RENEW("srs-dom-renew"),
    DOMAIN_RGP_RESTORE_REPORT("srs-dom-rgp-restore-report"),
    DOMAIN_RGP_RESTORE_REQUEST("srs-dom-rgp-restore-request"),
    DOMAIN_TRANSFER_APPROVE("srs-dom-transfer-approve"),
    DOMAIN_TRANSFER_CANCEL("srs-dom-transfer-cancel"),
    DOMAIN_TRANSFER_QUERY("srs-dom-transfer-query"),
    DOMAIN_TRANSFER_REJECT("srs-dom-transfer-reject"),
    DOMAIN_TRANSFER_REQUEST("srs-dom-transfer-request"),
    DOMAIN_UPDATE("srs-dom-update"),  // Note: does not include domain restore requests.
    HOST_CHECK("srs-host-check"),
    HOST_CREATE("srs-host-create"),
    HOST_DELETE("srs-host-delete"),
    HOST_INFO("srs-host-info"),
    HOST_UPDATE("srs-host-update"),
    CONTACT_CHECK("srs-cont-check"),
    CONTACT_CREATE("srs-cont-create"),
    CONTACT_DELETE("srs-cont-delete"),
    CONTACT_INFO("srs-cont-info"),
    CONTACT_TRANSFER_APPROVE("srs-cont-transfer-approve"),
    CONTACT_TRANSFER_CANCEL("srs-cont-transfer-cancel"),
    CONTACT_TRANSFER_QUERY("srs-cont-transfer-query"),
    CONTACT_TRANSFER_REJECT("srs-cont-transfer-reject"),
    CONTACT_TRANSFER_REQUEST("srs-cont-transfer-request"),
    CONTACT_UPDATE("srs-cont-update");

    /** Returns the actual field name from the specification. */
    private final String fieldName;

    ActivityReportField(String fieldName) {
      this.fieldName = fieldName;
    }

    public String getFieldName() {
      return fieldName;
    }
  }
}
