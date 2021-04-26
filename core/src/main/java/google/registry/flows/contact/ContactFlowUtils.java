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

import static google.registry.model.contact.PostalInfo.Type.INTERNATIONALIZED;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValuePolicyErrorException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.poll.PendingActionNotificationResponse.ContactPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse.ContactTransferResponse;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Static utility functions for contact flows. */
public class ContactFlowUtils {
  /** Check that an internationalized postal info has only ascii characters. */
  static void validateAsciiPostalInfo(@Nullable PostalInfo internationalized) throws EppException {
    if (internationalized != null) {
      Preconditions.checkState(INTERNATIONALIZED.equals(internationalized.getType()));
      ContactAddress address = internationalized.getAddress();
      Set<String> fields = Sets.newHashSet(
          internationalized.getName(),
          internationalized.getOrg(),
          address.getCity(),
          address.getCountryCode(),
          address.getState(),
          address.getZip());
      fields.addAll(address.getStreet());
      for (String field : fields) {
        if (field != null && !CharMatcher.ascii().matchesAllOf(field)) {
          throw new BadInternationalizedPostalInfoException();
        }
      }
    }
  }

  /** Check contact's state against server policy. */
  static void validateContactAgainstPolicy(ContactResource contact) throws EppException {
    if (contact.getDisclose() != null && !contact.getDisclose().getFlag()) {
      throw new DeclineContactDisclosureFieldDisallowedPolicyException();
    }
  }

  /** Create a poll message for the gaining client in a transfer. */
  static PollMessage createGainingTransferPollMessage(
      String targetId,
      TransferData transferData,
      DateTime now,
      Key<ContactHistory> contactHistoryKey) {
    return new PollMessage.OneTime.Builder()
        .setClientId(transferData.getGainingClientId())
        .setEventTime(transferData.getPendingTransferExpirationTime())
        .setMsg(transferData.getTransferStatus().getMessage())
        .setResponseData(
            ImmutableList.of(
                createTransferResponse(targetId, transferData),
                ContactPendingActionNotificationResponse.create(
                    targetId,
                    transferData.getTransferStatus().isApproved(),
                    transferData.getTransferRequestTrid(),
                    now)))
        .setParentKey(contactHistoryKey)
        .build();
  }

  /** Create a poll message for the losing client in a transfer. */
  static PollMessage createLosingTransferPollMessage(
      String targetId, TransferData transferData, Key<ContactHistory> contactHistoryKey) {
    return new PollMessage.OneTime.Builder()
        .setClientId(transferData.getLosingClientId())
        .setEventTime(transferData.getPendingTransferExpirationTime())
        .setMsg(transferData.getTransferStatus().getMessage())
        .setResponseData(ImmutableList.of(createTransferResponse(targetId, transferData)))
        .setParentKey(contactHistoryKey)
        .build();
  }

  /** Create a {@link ContactTransferResponse} off of the info in a {@link TransferData}. */
  static ContactTransferResponse createTransferResponse(
      String targetId, TransferData transferData) {
    return new ContactTransferResponse.Builder()
        .setContactId(targetId)
        .setGainingClientId(transferData.getGainingClientId())
        .setLosingClientId(transferData.getLosingClientId())
        .setPendingTransferExpirationTime(transferData.getPendingTransferExpirationTime())
        .setTransferRequestTime(transferData.getTransferRequestTime())
        .setTransferStatus(transferData.getTransferStatus())
        .build();
  }

  /** Declining contact disclosure is disallowed by server policy. */
  static class DeclineContactDisclosureFieldDisallowedPolicyException
      extends ParameterValuePolicyErrorException {
    public DeclineContactDisclosureFieldDisallowedPolicyException() {
      super("Declining contact disclosure is disallowed by server policy.");
    }
  }

  /** Internationalized postal infos can only contain ASCII characters. */
  static class BadInternationalizedPostalInfoException extends ParameterValueSyntaxErrorException {
    public BadInternationalizedPostalInfoException() {
      super("Internationalized postal infos can only contain ASCII characters");
    }
  }
}
