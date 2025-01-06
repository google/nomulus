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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Fact.simpleFact;
import static com.google.common.truth.Truth.assertAbout;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import google.registry.model.contact.Contact;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.testing.TruthChainer.And;
import org.joda.time.DateTime;

/** Truth subject for asserting things about {@link Contact} entities. */
public final class ContactSubject extends AbstractEppResourceSubject<Contact, ContactSubject> {

  private final Contact actual;

  public ContactSubject(FailureMetadata failureMetadata, Contact subject) {
    super(failureMetadata, checkNotNull(subject));
    this.actual = subject;
  }

  public And<ContactSubject> hasLocalizedPostalInfo(PostalInfo postalInfo) {
    return hasValue(postalInfo, actual.getLocalizedPostalInfo(), "has localizedPostalInfo");
  }

  public And<ContactSubject> hasNullLocalizedPostalInfo() {
    if (actual.getLocalizedPostalInfo() != null) {
      failWithActual(simpleFact("expected to have null localized postal info"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasNonNullLocalizedPostalInfo() {
    if (actual.getLocalizedPostalInfo() == null) {
      failWithActual(simpleFact("expected to have non-null localized postal info"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasInternationalizedPostalInfo(PostalInfo postalInfo) {
    return hasValue(
        postalInfo, actual.getInternationalizedPostalInfo(), "has internationalizedPostalInfo");
  }

  public And<ContactSubject> hasNullInternationalizedPostalInfo() {
    if (actual.getInternationalizedPostalInfo() != null) {
      failWithActual(simpleFact("expected to have null internationalized postal info"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasNonNullInternationalizedPostalInfo() {
    if (actual.getInternationalizedPostalInfo() == null) {
      failWithActual(simpleFact("expected to have non-null internationalized postal info"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasNullEmailAddress() {
    if (actual.getEmailAddress() != null) {
      failWithActual(simpleFact("expected to have null email address"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasNonNullEmailAddress() {
    if (actual.getEmailAddress() == null) {
      failWithActual(simpleFact("expected to have non-null email address"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasNullVoiceNumber() {
    if (actual.getVoiceNumber() != null) {
      failWithActual(simpleFact("expected to have null voice number"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasNonNullVoiceNumber() {
    if (actual.getVoiceNumber() == null) {
      failWithActual(simpleFact("expected to have non-null voice number"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasNullFaxNumber() {
    if (actual.getFaxNumber() != null) {
      failWithActual(simpleFact("expected to have null fax number"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasNonNullFaxNumber() {
    if (actual.getFaxNumber() == null) {
      failWithActual(simpleFact("expected to have non-null fax number"));
    }
    return andChainer();
  }

  public And<ContactSubject> hasAuthInfoPwd(String pw) {
    AuthInfo authInfo = actual.getAuthInfo();
    return hasValue(pw, authInfo == null ? null : authInfo.getPw().getValue(), "has auth info pw");
  }

  public And<ContactSubject> hasLastTransferTime(DateTime lastTransferTime) {
    return hasValue(lastTransferTime, actual.getLastTransferTime(), "has lastTransferTime");
  }

  public And<ContactSubject> hasLastTransferTimeNotEqualTo(DateTime lastTransferTime) {
    return doesNotHaveValue(lastTransferTime, actual.getLastTransferTime(), "lastTransferTime");
  }

  public And<ContactSubject> hasCurrentSponsorRegistrarId(String registrarId) {
    return hasValue(
        registrarId, actual.getCurrentSponsorRegistrarId(), "has currentSponsorRegistrarId");
  }

  public static SimpleSubjectBuilder<ContactSubject, Contact> assertAboutContacts() {
    return assertAbout(ContactSubject::new);
  }
}
