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

package google.registry.flows.contact;


import google.registry.flows.EppException;
import google.registry.flows.Flow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.exceptions.ContactsProhibitedException;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import jakarta.inject.Inject;

/**
 * An EPP flow that is meant to reject a pending transfer on a contact.
 *
 * <p>This is no longer in use, because Nomulus follows the Minimum Dataset requirements, removing
 * all contact information.
 *
 * @error {@link ContactsProhibitedException}
 */
@Deprecated
@ReportingSpec(ActivityReportField.CONTACT_TRANSFER_REJECT)
public final class ContactTransferRejectFlow implements Flow {

  @Inject ContactTransferRejectFlow() {}

  @Override
  public EppResponse run() throws EppException {
    throw new ContactsProhibitedException();
  }
}
