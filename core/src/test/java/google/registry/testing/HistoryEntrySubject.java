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

import static com.google.common.truth.Fact.simpleFact;
import static com.google.common.truth.Truth.assertAbout;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import com.google.common.truth.Subject;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.TruthChainer.And;
import java.util.Optional;
import org.joda.time.DateTime;

/** Utility methods for asserting things about {@link HistoryEntry} instances. */
public class HistoryEntrySubject extends Subject {

  private final HistoryEntry actual;
  private String customDisplaySubject;

  public HistoryEntrySubject(FailureMetadata failureMetadata, HistoryEntry subject) {
    super(failureMetadata, subject);
    this.actual = subject;
  }

  @Override
  protected String actualCustomStringRepresentation() {
    return Optional.ofNullable(customDisplaySubject).orElse(String.valueOf(actual));
  }

  public HistoryEntrySubject withCustomDisplaySubject(String customDisplaySubject) {
    this.customDisplaySubject = customDisplaySubject;
    return this;
  }

  public And<HistoryEntrySubject> hasType(HistoryEntry.Type type) {
    return hasValue(type, actual.getType(), "getType()");
  }

  public And<HistoryEntrySubject> hasRegistrarId(String registrarId) {
    return hasValue(registrarId, actual.getRegistrarId(), "getRegistrarId()");
  }

  public And<HistoryEntrySubject> hasOtherRegistrarId(String otherRegistrarId) {
    if (!(actual instanceof DomainHistory)) {
      failWithActual(simpleFact("expected to be DomainHistory"));
    }
    return hasValue(
        otherRegistrarId, ((DomainHistory) actual).getOtherRegistrarId(), "getOtherRegistrarId()");
  }

  public And<HistoryEntrySubject> hasModificationTime(DateTime modificationTime) {
    return hasValue(modificationTime, actual.getModificationTime(), "getModificationTime()");
  }

  public And<HistoryEntrySubject> bySuperuser(boolean superuser) {
    return hasValue(superuser, actual.getBySuperuser(), "getBySuperuser()");
  }

  public And<HistoryEntrySubject> hasPeriod() {
    if (!(actual instanceof DomainHistory)) {
      failWithActual(simpleFact("expected to be DomainHistory"));
    }
    if (((DomainHistory) actual).getPeriod() == null) {
      failWithActual(simpleFact("expected to have a period"));
    }
    return new And<>(this);
  }

  public And<HistoryEntrySubject> hasPeriodYears(int years) {
    if (!(actual instanceof DomainHistory)) {
      failWithActual(simpleFact("expected to be DomainHistory"));
    }
    Period actualPeriod = ((DomainHistory) actual).getPeriod();
    return hasPeriod()
        .and()
        .hasValue(Period.Unit.YEARS, actualPeriod.getUnit(), "getPeriod().getUnit()")
        .and()
        .hasValue(years, actualPeriod.getValue(), "getPeriod().getValue()");
  }

  public And<HistoryEntrySubject> hasNoXml() {
    if (actual.getXmlBytes() != null) {
      failWithActual(simpleFact("expected to have no xml"));
    }
    return new And<>(this);
  }

  public And<HistoryEntrySubject> hasMetadataReason(String reason) {
    return hasValue(reason, actual.getReason(), "getReason()");
  }

  public And<HistoryEntrySubject> hasMetadataRequestedByRegistrar(boolean requestedByRegistrar) {
    return hasValue(
        requestedByRegistrar, actual.getRequestedByRegistrar(), "getRequestedByRegistrar()");
  }

  protected <E> And<HistoryEntrySubject> hasValue(E expected, E actual, String name) {
    check(name).that(actual).isEqualTo(expected);
    return new And<>(this);
  }

  public static SimpleSubjectBuilder<HistoryEntrySubject, HistoryEntry>
      assertAboutHistoryEntries() {
    return assertAbout(historyEntries());
  }

  public static Factory<HistoryEntrySubject, HistoryEntry> historyEntries() {
    return HistoryEntrySubject::new;
  }
}
