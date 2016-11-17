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
import google.registry.model.domain.DomainResource;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.TruthChainer.And;
import java.util.Objects;
import org.joda.time.DateTime;

/** Truth subject for asserting things about {@link DomainResource} instances. */
public final class DomainResourceSubject
    extends AbstractDomainBaseSubject<DomainResource, DomainResourceSubject> {

  /** A factory for instances of this subject. */
  private static class SubjectFactory
      extends ReflectiveSubjectFactory<DomainResource, DomainResourceSubject>{}

  public And<DomainResourceSubject> hasTransferStatus(TransferStatus transferStatus) {
    return hasValue(
        transferStatus,
        actual().getTransferData().getTransferStatus(),
        "has transferStatus");
  }

  public And<DomainResourceSubject> hasTransferRequestClientTrid(String clTrid) {
    return hasValue(
        clTrid,
        actual().getTransferData().getTransferRequestTrid().getClientTransactionId(),
        "has trid");
  }

  public And<DomainResourceSubject> hasPendingTransferExpirationTime(
      DateTime pendingTransferExpirationTime) {
    return hasValue(
        pendingTransferExpirationTime,
        actual().getTransferData().getPendingTransferExpirationTime(),
        "has pendingTransferExpirationTime");
  }

  public And<DomainResourceSubject> hasTransferGainingClientId(String gainingClientId) {
    return hasValue(
        gainingClientId,
        actual().getTransferData().getGainingClientId(),
        "has transfer ga");
  }

  public And<DomainResourceSubject> hasTransferLosingClientId(String losingClientId) {
    return hasValue(
        losingClientId,
        actual().getTransferData().getLosingClientId(),
        "has transfer losingClientId");
  }

  public And<DomainResourceSubject> hasRegistrationExpirationTime(DateTime expiration) {
    if (!Objects.equals(actual().getRegistrationExpirationTime(), expiration)) {
      failWithBadResults(
          "has registrationExpirationTime",
          expiration,
          actual().getRegistrationExpirationTime());
    }
    return andChainer();
  }

  public And<DomainResourceSubject> hasLastTransferTime(DateTime lastTransferTime) {
    return hasValue(
        lastTransferTime,
        actual().getLastTransferTime(),
        "has lastTransferTime");
  }

  public And<DomainResourceSubject> hasLastTransferTimeNotEqualTo(DateTime lastTransferTime) {
    return doesNotHaveValue(
        lastTransferTime,
        actual().getLastTransferTime(),
        "lastTransferTime");
  }

  public And<DomainResourceSubject> hasDeletePollMessage() {
    if (actual().getDeletePollMessage() == null) {
      fail("has a delete poll message");
    }
    return andChainer();
  }

  public And<DomainResourceSubject> hasNoDeletePollMessage() {
    if (actual().getDeletePollMessage() != null) {
      fail("has no delete poll message");
    }
    return andChainer();
  }

  public And<DomainResourceSubject> hasSmdId(String smdId) {
    return hasValue(smdId, actual().getSmdId(), "has smdId");
  }

  public DomainResourceSubject(FailureStrategy strategy, DomainResource subject) {
    super(strategy, checkNotNull(subject));
  }

  public static DelegatedVerb<DomainResourceSubject, DomainResource> assertAboutDomains() {
    return assertAbout(new SubjectFactory());
  }
}
