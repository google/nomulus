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
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.testing.TruthChainer.And;
import java.util.Set;
import org.joda.time.DateTime;

/** Truth subject for asserting things about {@link DomainBase} instances. */
public final class DomainBaseSubject
    extends AbstractEppResourceSubject<DomainBase, DomainBaseSubject> {

  private final DomainBase actual;

  public DomainBaseSubject(FailureMetadata failureMetadata, DomainBase subject) {
    super(failureMetadata, checkNotNull(subject));
    this.actual = subject;
  }

  public And<DomainBaseSubject> hasFullyQualifiedDomainName(String fullyQualifiedDomainName) {
    return hasValue(
        fullyQualifiedDomainName, actual.getDomainName(), "has fullyQualifiedDomainName");
  }

  public And<DomainBaseSubject> hasExactlyDsData(DelegationSignerData... dsData) {
    return hasExactlyDsData(ImmutableSet.copyOf(dsData));
  }

  public And<DomainBaseSubject> hasExactlyDsData(Set<DelegationSignerData> dsData) {
    return hasValue(dsData, actual.getDsData(), "has dsData");
  }

  public And<DomainBaseSubject> hasNumDsData(int num) {
    return hasValue(num, actual.getDsData().size(), "has num dsData");
  }

  public And<DomainBaseSubject> hasLaunchNotice(LaunchNotice launchNotice) {
    return hasValue(launchNotice, actual.getLaunchNotice(), "has launchNotice");
  }

  public And<DomainBaseSubject> hasAuthInfoPwd(String pw) {
    AuthInfo authInfo = actual.getAuthInfo();
    return hasValue(pw, authInfo == null ? null : authInfo.getPw().getValue(), "has auth info pw");
  }

  public And<DomainBaseSubject> hasCurrentSponsorClientId(String clientId) {
    return hasValue(clientId, actual.getCurrentSponsorClientId(), "has currentSponsorClientId");
  }

  public And<DomainBaseSubject> hasRegistrationExpirationTime(DateTime expiration) {
    return hasValue(
        expiration, actual.getRegistrationExpirationTime(), "getRegistrationExpirationTime()");
  }

  public And<DomainBaseSubject> hasLastTransferTime(DateTime lastTransferTime) {
    return hasValue(lastTransferTime, actual.getLastTransferTime(), "getLastTransferTime()");
  }

  public And<DomainBaseSubject> hasLastTransferTimeNotEqualTo(DateTime lastTransferTime) {
    return doesNotHaveValue(
        lastTransferTime, actual.getLastTransferTime(), "getLastTransferTime()");
  }

  public And<DomainBaseSubject> hasDeletePollMessage() {
    if (actual.getDeletePollMessage() == null) {
      failWithActual(simpleFact("expected to have a delete poll message"));
    }
    return andChainer();
  }

  public And<DomainBaseSubject> hasNoDeletePollMessage() {
    if (actual.getDeletePollMessage() != null) {
      failWithActual(simpleFact("expected to have no delete poll message"));
    }
    return andChainer();
  }

  public And<DomainBaseSubject> hasSmdId(String smdId) {
    return hasValue(smdId, actual.getSmdId(), "getSmdId()");
  }

  public And<DomainBaseSubject> hasAutorenewEndTime(DateTime autorenewEndTime) {
    checkArgumentNotNull(autorenewEndTime, "Use hasNoAutorenewEndTime() instead");
    return hasValue(autorenewEndTime, actual.getAutorenewEndTime(), "getAutorenewEndTime()");
  }

  public And<DomainBaseSubject> hasNoAutorenewEndTime() {
    return hasNoValue(actual.getAutorenewEndTime(), "getAutorenewEndTime()");
  }

  public static SimpleSubjectBuilder<DomainBaseSubject, DomainBase> assertAboutDomains() {
    return assertAbout(DomainBaseSubject::new);
  }
}
