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

package google.registry.rdap;

import com.google.common.collect.ImmutableList;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.Notice;
import google.registry.rdap.RdapDataStructures.Remark;

/**
 * This file contains boilerplate required by the ICANN RDAP Profile.
 *
 * @see <a href="https://www.icann.org/resources/pages/rdap-operational-profile-2016-07-26-en">RDAP
 *     Operational Profile for gTLD Registries and Registrars</a>
 */
public class RdapIcannStandardInformation {

  /** Required by ICANN RDAP Profile section 1.4.10. */
  private static final Notice CONFORMANCE_NOTICE =
      Notice.builder()
          .setDescription(
              "This response conforms to the RDAP Operational Profile for gTLD Registries and"
                  + " Registrars version 1.0")
          .build();

  /** Required by ICANN RDAP Profile section 1.5.18. */
  private static final Notice DOMAIN_STATUS_CODES_NOTICE =
      Notice.builder()
          .setTitle("Status Codes")
          .setDescription(
              "For more information on domain status codes, please visit"
                  + " https://icann.org/epp")
          .addLink(
              Link.builder()
                  .setRel("alternate")
                  .setHref("https://icann.org/epp")
                  .setType("text/html")
                  .build())
          .build();

  /** Required by ICANN RDAP Response Profile section 2.11. */
  private static final Notice INACCURACY_COMPLAINT_FORM_NOTICE =
      Notice.builder()
          .setTitle("RDDS Inaccuracy Complaint Form")
          .setDescription("URL of the ICANN RDDS Inaccuracy Complaint Form: https://icann.org/wicf")
          .addLink(
              Link.builder()
                  .setRel("alternate")
                  .setHref("https://icann.org/wicf")
                  .setType("text/html")
                  .build())
          .build();

  /** Boilerplate notices required by domain responses. */
  static final ImmutableList<Notice> domainBoilerplateNotices =
      ImmutableList.of(
          CONFORMANCE_NOTICE,
          // RDAP Response Profile 2.6.3
          DOMAIN_STATUS_CODES_NOTICE,
          // RDAP Response Profile 2.11
          INACCURACY_COMPLAINT_FORM_NOTICE);

  /** Boilerplate remarks required by nameserver and entity responses. */
  static final ImmutableList<Notice> nameserverAndEntityBoilerplateNotices =
      ImmutableList.of(CONFORMANCE_NOTICE);

  /**
   * Required by ICANN RDAP Profile section 1.4.9, as corrected by Gustavo Lozano of ICANN.
   *
   * <p>Also mentioned in the RDAP Technical Implementation Guide 3.6.
   *
   * @see <a href="http://mm.icann.org/pipermail/gtld-tech/2016-October/000822.html">Questions about
   *     the ICANN RDAP Profile</a>
   */
  static final Remark SUMMARY_DATA_REMARK =
      Remark.builder()
          .setTitle("Incomplete Data")
          .setDescription(
              "Summary data only. For complete data, send a specific query for the object.")
          .setType(Remark.Type.OBJECT_TRUNCATED_UNEXPLAINABLE)
          .build();

  /**
   * Required by ICANN RDAP Profile section 1.4.8, as corrected by Gustavo Lozano of ICANN.
   *
   * <p>Also mentioned in the RDAP Technical Implementation Guide 3.5.
   *
   * @see <a href="http://mm.icann.org/pipermail/gtld-tech/2016-October/000822.html">Questions about
   *     the ICANN RDAP Profile</a>
   */
  static final Notice TRUNCATED_RESULT_SET_NOTICE =
      Notice.builder()
          .setTitle("Search Policy")
          .setDescription("Search results per query are limited.")
          .setType(Notice.Type.RESULT_TRUNCATED_UNEXPLAINABLE)
          .build();

  /** Truncation notice as a singleton list, for easy use. */
  static final ImmutableList<Notice> TRUNCATION_NOTICES =
      ImmutableList.of(TRUNCATED_RESULT_SET_NOTICE);

  /**
   * Used when a search for domains by nameserver may have returned incomplete information because
   * there were too many nameservers in the first stage results.
   */
  static final Notice POSSIBLY_INCOMPLETE_RESULT_SET_NOTICE =
      Notice.builder()
          .setTitle("Search Policy")
          .setDescription(
                  "Search results may contain incomplete information due to first-stage query"
                      + " limits.")
          .setType(Notice.Type.RESULT_TRUNCATED_UNEXPLAINABLE)
          .build();

  /** Possibly incomplete notice as a singleton list, for easy use. */
  static final ImmutableList<Notice> POSSIBLY_INCOMPLETE_NOTICES =
      ImmutableList.of(POSSIBLY_INCOMPLETE_RESULT_SET_NOTICE);

  /**
   * Included when requester is not logged in as the owner of the contact being returned.
   *
   * <p>Format required by ICANN RDAP Response Profile 15feb19 section 2.7.4.3.
   */
  static final Remark CONTACT_PERSONAL_DATA_HIDDEN_DATA_REMARK =
      Remark.builder()
          .setTitle("REDACTED FOR PRIVACY")
          .setDescription(
              "Some of the data in this object has been removed.",
              "Contact personal data is visible only to the owning registrar.")
          .setType(Remark.Type.OBJECT_REDACTED_AUTHORIZATION)
          .addLink(
              Link.builder()
                  .setRel("alternate")
                  .setHref(
                      "https://github.com/google/nomulus/blob/master/docs/rdap.md#authentication")
                  .setType("text/html")
                  .build())
          .build();

  /**
   * Included in ALL contact responses, even if the user is authorized.
   *
   * <p>Format required by ICANN RDAP Response Profile 15feb19 section 2.7.5.3.
   *
   * <p>NOTE that unlike other redacted fields, there's no allowance to give the email to authorized
   * users or allow for registrar consent.
   */
  static final Remark CONTACT_EMAIL_REDACTED_FOR_DOMAIN =
      Remark.builder()
          .setTitle("EMAIL REDACTED FOR PRIVACY")
          .setDescription(
              "Please query the RDDS service of the Registrar of Record identifies in this output"
                  + " for information on how to contact the Registrant of the queried domain"
                  + " name.")
          .setType(Remark.Type.OBJECT_REDACTED_AUTHORIZATION)
          .build();
}
