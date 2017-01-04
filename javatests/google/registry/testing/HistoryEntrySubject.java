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

import static com.google.common.truth.Truth.assertAbout;

import com.google.common.base.Optional;
import com.google.common.truth.AbstractVerb.DelegatedVerb;
import com.google.common.truth.FailureStrategy;
import com.google.common.truth.Subject;
import google.registry.model.domain.Period;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.TruthChainer.And;
import java.util.Objects;

/** Utility methods for asserting things about {@link HistoryEntry} instances. */
public class HistoryEntrySubject extends Subject<HistoryEntrySubject, HistoryEntry> {

  private String customDisplaySubject;

  /** A factory for instances of this subject. */
  private static class SubjectFactory
      extends ReflectiveSubjectFactory<HistoryEntry, HistoryEntrySubject>{}

  public HistoryEntrySubject(FailureStrategy strategy, HistoryEntry subject) {
    super(strategy, subject);
  }

  @Override
  public String actualCustomStringRepresentation() {
    return Optional.fromNullable(customDisplaySubject).or(super.actualAsString());
  }

  public HistoryEntrySubject withCustomDisplaySubject(String customDisplaySubject) {
    this.customDisplaySubject = customDisplaySubject;
    return this;
  }

  public And<HistoryEntrySubject> hasType(HistoryEntry.Type type) {
    return hasValue(type, actual().getType(), "has type");
  }

  public And<HistoryEntrySubject> hasClientId(String clientId) {
    return hasValue(clientId, actual().getClientId(), "has client ID");
  }

  public And<HistoryEntrySubject> hasOtherClientId(String otherClientId) {
    return hasValue(otherClientId, actual().getOtherClientId(), "has other client ID");
  }

  public And<HistoryEntrySubject> hasPeriod() {
    if (actual().getPeriod() == null) {
      fail("has a period");
    }
    return new And<>(this);
  }

  public And<HistoryEntrySubject> hasPeriodYears(int years) {
    return hasPeriod().and()
        .hasValue(Period.Unit.YEARS, actual().getPeriod().getUnit(), "has period in").and()
        .hasValue(years, actual().getPeriod().getValue(), "has period length");
  }

  public And<HistoryEntrySubject> hasNoXml() {
    if (actual().getXmlBytes() != null) {
      fail("has no xml");
    }
    return new And<>(this);
  }

  public And<HistoryEntrySubject> hasMetadataReason(String reason) {
    return hasValue(reason, actual().getReason(), "has metadata reason");
  }

  public And<HistoryEntrySubject> hasMetadataRequestedByRegistrar(
        boolean requestedByRegistrar) {
    if (actual().getRequestedByRegistrar() != requestedByRegistrar) {
      fail("has metadata requestedByRegistrar with value", requestedByRegistrar);
    }
    return new And<>(this);
  }

  protected void failWithBadResults(String dualVerb, Object expected, Object actual) {
    failWithBadResults(dualVerb, expected, dualVerb, actual);
  }

  protected <E> And<HistoryEntrySubject> hasValue(E expected, E actual, String verb) {
    if (!Objects.equals(expected, actual)) {
      failWithBadResults(verb, expected, actual);
    }
    return new And<>(this);
  }

  public static DelegatedVerb<HistoryEntrySubject, HistoryEntry> assertAboutHistoryEntries() {
    return assertAbout(new SubjectFactory());
  }
}
