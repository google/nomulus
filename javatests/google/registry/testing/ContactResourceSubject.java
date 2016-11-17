// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertAbout;

import com.google.common.truth.AbstractVerb.DelegatedVerb;
import com.google.common.truth.FailureStrategy;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.TruthChainer.And;
import org.joda.time.DateTime;

/** Truth subject for asserting things about {@link ContactResource} instances. */
public final class ContactResourceSubject
    extends AbstractEppResourceSubject<ContactResource, ContactResourceSubject> {

  /** A factory for instances of this subject. */
  private static class SubjectFactory
      extends ReflectiveSubjectFactory<ContactResource, ContactResourceSubject>{}

  public ContactResourceSubject(FailureStrategy strategy, ContactResource subject) {
    super(strategy, checkNotNull(subject));
  }

  public And<ContactResourceSubject> hasLocalizedPostalInfo(PostalInfo postalInfo) {
    return hasValue(postalInfo, actual().getLocalizedPostalInfo(), "has localizedPostalInfo");
  }

  public And<ContactResourceSubject> hasNullLocalizedPostalInfo() {
    if (actual().getLocalizedPostalInfo() != null) {
      fail("has null localized postal info");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNonNullLocalizedPostalInfo() {
    if (actual().getLocalizedPostalInfo() == null) {
      fail("has non-null localized postal info");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasInternationalizedPostalInfo(
      PostalInfo postalInfo) {
    return hasValue(
        postalInfo,
        actual().getInternationalizedPostalInfo(),
        "has internationalizedPostalInfo");
  }

  public And<ContactResourceSubject> hasNullInternationalizedPostalInfo() {
    if (actual().getInternationalizedPostalInfo() != null) {
      fail("has null internationalized postal info");
    }
    return andChainer();
  }


  public And<ContactResourceSubject> hasNonNullInternationalizedPostalInfo() {
    if (actual().getInternationalizedPostalInfo() == null) {
      fail("has non-null internationalized postal info");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNullEmailAddress() {
    if (actual().getEmailAddress() != null) {
      fail("has null email address");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNonNullEmailAddress() {
    if (actual().getEmailAddress() == null) {
      fail("has non-null email address");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNullVoiceNumber() {
    if (actual().getVoiceNumber() != null) {
      fail("has null voice number");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNonNullVoiceNumber() {
    if (actual().getVoiceNumber() == null) {
      fail("has non-null voice number");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNullFaxNumber() {
    if (actual().getFaxNumber() != null) {
      fail("has null fax number");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNonNullFaxNumber() {
    if (actual().getFaxNumber() == null) {
      fail("has non-null fax number");
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasAuthInfoPwd(String pw) {
    AuthInfo authInfo = actual().getAuthInfo();
    return hasValue(pw, authInfo == null ? null : authInfo.getPw().getValue(), "has auth info pw");
  }

  public And<ContactResourceSubject> hasTransferStatus(TransferStatus transferStatus) {
    return hasValue(
        transferStatus,
        actual().getTransferData().getTransferStatus(),
        "has transferStatus");
  }

  public And<ContactResourceSubject> hasTransferRequestClientTrid(String clTrid) {
    return hasValue(
        clTrid,
        actual().getTransferData().getTransferRequestTrid().getClientTransactionId(),
        "has trid");
  }

  public And<ContactResourceSubject> hasPendingTransferExpirationTime(
      DateTime pendingTransferExpirationTime) {
    return hasValue(
        pendingTransferExpirationTime,
        actual().getTransferData().getPendingTransferExpirationTime(),
        "has pendingTransferExpirationTime");
  }

  public And<ContactResourceSubject> hasTransferGainingClientId(String gainingClientId) {
    return hasValue(
        gainingClientId,
        actual().getTransferData().getGainingClientId(),
        "has transfer ga");
  }

  public And<ContactResourceSubject> hasTransferLosingClientId(String losingClientId) {
    return hasValue(
        losingClientId,
        actual().getTransferData().getLosingClientId(),
        "has transfer losingClientId");
  }

  public And<ContactResourceSubject> hasLastTransferTime(DateTime lastTransferTime) {
    return hasValue(
        lastTransferTime,
        actual().getLastTransferTime(),
        "has lastTransferTime");
  }

  public And<ContactResourceSubject> hasLastTransferTimeNotEqualTo(DateTime lastTransferTime) {
    return doesNotHaveValue(
        lastTransferTime,
        actual().getLastTransferTime(),
        "lastTransferTime");
  }
  public static DelegatedVerb<ContactResourceSubject, ContactResource> assertAboutContacts() {
    return assertAbout(new SubjectFactory());
  }
}
