// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.console;

/** Permissions that users may have in the UI, either per-registrar or globally. */
public enum ConsolePermission {
  AUDIT_ACTIVITY_BY_USER,
  AUDIT_ACTIVITY_BY_REGISTRAR,
  /** View basic information about a registrar. */
  VIEW_REGISTRAR_DETAILS,
  /** Edit basic information about a registrar. */
  EDIT_REGISTRAR_DETAILS,
  /** Add, update, or remove other console users. */
  MANAGE_USERS,
  /** View registrars. */
  VIEW_REGISTRARS,
  /** Add, update, or remove registrars. */
  MANAGE_REGISTRARS,
  /** Manage related registrars, e.g. when one registrar owns another. */
  MANAGE_ACCREDITATION,
  /** Set up the EPP connection (e.g. certs). */
  CONFIGURE_EPP_CONNECTION,
  /** Retrieve the unredacted registrant email from a domain. */
  GET_REGISTRANT_EMAIL,
  /** Suspend a domain for compliance (non-URS) reasons. */
  SUSPEND_DOMAIN,
  /** Suspend a domain for the Uniform Rapid Suspension process. */
  SUSPEND_DOMAIN_URS,
  /** Download a list of domains under management. */
  DOWNLOAD_DOMAINS,
  /** Change the password for a registrar. */
  CHANGE_NOMULUS_PASSWORD,
  /** View all possible TLDs. */
  VIEW_TLD_PORTFOLIO,
  /** Onboarding the registrar(s) to extra programs like registry locking. */
  ONBOARD_ADDITIONAL_PROGRAMS,
  /** Execute arbitrary EPP commands through the UI. */
  EXECUTE_EPP_COMMANDS,
  /** Send messages to the registry support team. */
  CONTACT_SUPPORT,
  /** Access billing and payment details for a registrar. */
  ACCESS_BILLING_DETAILS,
  /** Access any available documentation about the registry. */
  ACCESS_DOCUMENTATION,
  /** Change the documentation available in the UI about the registry. */
  MANAGE_DOCUMENTATION,
  /** Viewing the onboarding status of a registrar on a TLD. */
  CHECK_ONBOARDING_STATUS,
  /** View premium and/or reserved lists for a TLD. */
  VIEW_PREMIUM_RESERVED_LISTS,
  /** Sign and/or change legal documents like RRAs. */
  UPLOAD_CONTRACTS,
  /** Viewing legal documents like RRAs. */
  ACCESS_CONTRACTS,
  /** View analytics on operations and billing data (possibly TBD). */
  VIEW_OPERATIONAL_DATA,
  /** Create announcements that can be displayed in the UI. */
  SEND_ANNOUNCEMENTS,
  /** View announcements in the UI. */
  VIEW_ANNOUNCEMENTS,
  /** Viewing a record of actions performed in the UI for a particular registrar. */
  VIEW_ACTIVITY_LOG,
  /** View and perform registry locks. */
  REGISTRY_LOCK
}
