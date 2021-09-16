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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Fact.simpleFact;
import static com.google.common.truth.Truth.assertAbout;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.testing.TruthChainer.And;
import org.joda.time.DateTime;

/** Truth subject for asserting things about {@link ContactResource} instances. */
public final class ContactResourceSubject
    extends AbstractEppResourceSubject<ContactResource, ContactResourceSubject> {

  private final ContactResource actual;

  public ContactResourceSubject(FailureMetadata failureMetadata, ContactResource subject) {
    super(failureMetadata, checkNotNull(subject));
    this.actual = subject;
  }

  public And<ContactResourceSubject> hasLocalizedPostalInfo(PostalInfo postalInfo) {
    return hasValue(postalInfo, actual.getLocalizedPostalInfo(), "has localizedPostalInfo");
  }

  public And<ContactResourceSubject> hasNullLocalizedPostalInfo() {
    if (actual.getLocalizedPostalInfo() != null) {
      failWithActual(simpleFact("expected to have null localized postal info"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNonNullLocalizedPostalInfo() {
    if (actual.getLocalizedPostalInfo() == null) {
      failWithActual(simpleFact("expected to have non-null localized postal info"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasInternationalizedPostalInfo(
      PostalInfo postalInfo) {
    return hasValue(
        postalInfo, actual.getInternationalizedPostalInfo(), "has internationalizedPostalInfo");
  }

  public And<ContactResourceSubject> hasNullInternationalizedPostalInfo() {
    if (actual.getInternationalizedPostalInfo() != null) {
      failWithActual(simpleFact("expected to have null internationalized postal info"));
    }
    return andChainer();
  }


  public And<ContactResourceSubject> hasNonNullInternationalizedPostalInfo() {
    if (actual.getInternationalizedPostalInfo() == null) {
      failWithActual(simpleFact("expected to have non-null internationalized postal info"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNullEmailAddress() {
    if (actual.getEmailAddress() != null) {
      failWithActual(simpleFact("expected to have null email address"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNonNullEmailAddress() {
    if (actual.getEmailAddress() == null) {
      failWithActual(simpleFact("expected to have non-null email address"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNullVoiceNumber() {
    if (actual.getVoiceNumber() != null) {
      failWithActual(simpleFact("expected to have null voice number"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNonNullVoiceNumber() {
    if (actual.getVoiceNumber() == null) {
      failWithActual(simpleFact("expected to have non-null voice number"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNullFaxNumber() {
    if (actual.getFaxNumber() != null) {
      failWithActual(simpleFact("expected to have null fax number"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasNonNullFaxNumber() {
    if (actual.getFaxNumber() == null) {
      failWithActual(simpleFact("expected to have non-null fax number"));
    }
    return andChainer();
  }

  public And<ContactResourceSubject> hasAuthInfoPwd(String pw) {
    AuthInfo authInfo = actual.getAuthInfo();
    return hasValue(pw, authInfo == null ? null : authInfo.getPw().getValue(), "has auth info pw");
  }

  public And<ContactResourceSubject> hasLastTransferTime(DateTime lastTransferTime) {
    return hasValue(lastTransferTime, actual.getLastTransferTime(), "has lastTransferTime");
  }

  public And<ContactResourceSubject> hasLastTransferTimeNotEqualTo(DateTime lastTransferTime) {
    return doesNotHaveValue(lastTransferTime, actual.getLastTransferTime(), "lastTransferTime");
  }

  public And<ContactResourceSubject> hasCurrentSponsorRegistrarId(String registrarId) {
    return hasValue(
        registrarId, actual.getCurrentSponsorRegistrarId(), "has currentSponsorRegistrarId");
  }

  public static SimpleSubjectBuilder<ContactResourceSubject, ContactResource>
      assertAboutContacts() {
    return assertAbout(ContactResourceSubject::new);
  }
}
