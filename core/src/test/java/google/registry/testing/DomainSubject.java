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
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import google.registry.model.domain.Domain;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.testing.TruthChainer.And;
import google.registry.tmch.LordnTaskUtils.LordnPhase;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Truth subject for asserting things about {@link Domain} instances. */
public final class DomainSubject extends AbstractEppResourceSubject<Domain, DomainSubject> {

  private final Domain actual;

  public DomainSubject(FailureMetadata failureMetadata, Domain subject) {
    super(failureMetadata, checkNotNull(subject));
    this.actual = subject;
  }

  public And<DomainSubject> hasDomainName(String domainName) {
    return hasValue(domainName, actual.getDomainName(), "domainName");
  }

  public And<DomainSubject> hasExactlyDsData(DomainDsData... dsData) {
    return hasExactlyDsData(ImmutableSet.copyOf(dsData));
  }

  public And<DomainSubject> hasExactlyDsData(Set<DomainDsData> dsData) {
    return hasValue(dsData, actual.getDsData(), "dsData");
  }

  public And<DomainSubject> hasNumDsData(int num) {
    return hasValue(num, actual.getDsData().size(), "dsData.size()");
  }

  public And<DomainSubject> hasLaunchNotice(LaunchNotice launchNotice) {
    return hasValue(launchNotice, actual.getLaunchNotice(), "launchNotice");
  }

  public And<DomainSubject> hasLordnPhase(LordnPhase lordnPhase) {
    return hasValue(lordnPhase, actual.getLordnPhase(), "lordnPhase");
  }

  public And<DomainSubject> hasAuthInfoPwd(@Nullable String pw) {
    AuthInfo authInfo = actual.getAuthInfo();
    return hasValue(pw, authInfo == null ? null : authInfo.getPw().getValue(), "has auth info pw");
  }

  public And<DomainSubject> hasCurrentSponsorRegistrarId(String registrarId) {
    return hasValue(
        registrarId, actual.getCurrentSponsorRegistrarId(), "currentSponsorRegistrarId");
  }

  public And<DomainSubject> hasRegistrationExpirationTime(DateTime expiration) {
    return hasValue(
        expiration, actual.getRegistrationExpirationTime(), "getRegistrationExpirationTime()");
  }

  public And<DomainSubject> hasLastTransferTime(DateTime lastTransferTime) {
    return hasValue(lastTransferTime, actual.getLastTransferTime(), "getLastTransferTime()");
  }

  public And<DomainSubject> hasLastTransferTimeNotEqualTo(DateTime lastTransferTime) {
    return doesNotHaveValue(
        lastTransferTime, actual.getLastTransferTime(), "getLastTransferTime()");
  }

  public And<DomainSubject> hasDeletePollMessage() {
    if (actual.getDeletePollMessage() == null) {
      failWithActual(simpleFact("expected to have a delete poll message"));
    }
    return andChainer();
  }

  public And<DomainSubject> hasNoDeletePollMessage() {
    if (actual.getDeletePollMessage() != null) {
      failWithActual(simpleFact("expected to have no delete poll message"));
    }
    return andChainer();
  }

  public And<DomainSubject> hasSmdId(String smdId) {
    return hasValue(smdId, actual.getSmdId(), "getSmdId()");
  }

  public And<DomainSubject> hasAutorenewEndTime(DateTime autorenewEndTime) {
    checkArgumentNotNull(autorenewEndTime, "Use hasNoAutorenewEndTime() instead");
    return hasValue(autorenewEndTime, actual.getAutorenewEndTime(), "getAutorenewEndTime()");
  }

  public And<DomainSubject> hasNoAutorenewEndTime() {
    return hasNoValue(actual.getAutorenewEndTime(), "getAutorenewEndTime()");
  }

  public static SimpleSubjectBuilder<DomainSubject, Domain> assertAboutDomains() {
    return assertAbout(DomainSubject::new);
  }
}
