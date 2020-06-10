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
import static com.google.common.truth.Fact.fact;
import static com.google.common.truth.Fact.simpleFact;
import static google.registry.model.EppResourceUtils.isActive;
import static google.registry.testing.DatastoreHelper.getHistoryEntriesOfType;
import static google.registry.testing.HistoryEntrySubject.historyEntries;
import static google.registry.util.DiffUtils.prettyPrintEntityDeepDiff;

import com.google.common.collect.ImmutableSet;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.TruthChainer.And;
import google.registry.testing.TruthChainer.Which;
import java.util.List;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Base Truth subject for asserting things about epp resources. */
abstract class AbstractEppResourceSubject<
        T extends EppResource, S extends AbstractEppResourceSubject<T, S>>
    extends Subject {

  private final T actual;

  public AbstractEppResourceSubject(FailureMetadata failureMetadata, T subject) {
    super(failureMetadata, checkNotNull(subject));
    this.actual = subject;
  }

  private List<HistoryEntry> getHistoryEntries() {
    return DatastoreHelper.getHistoryEntries(actual);
  }

  @SuppressWarnings("unchecked")
  protected And<S> andChainer() {
    return new And<>((S) this);
  }

  @Override
  protected String actualCustomStringRepresentation() {
    return String.format(
        "%s with foreign key '%s'", actual.getClass().getSimpleName(), actual.getForeignKey());
  }

  @Override
  public void isEqualTo(@Nullable Object other) {
    // If the objects differ and we can show an interesting ImmutableObject diff, do so.
    if (actual != null && other instanceof ImmutableObject && !actual.equals(other)) {
      String diffText =
          prettyPrintEntityDeepDiff(
              ((ImmutableObject) other).toDiffableFieldMap(), actual.toDiffableFieldMap());
      failWithoutActual(fact("expected", other), fact("but was", actual), fact("diff", diffText));
    }
    // Otherwise, fall back to regular behavior.
    super.isEqualTo(other);
  }

  public And<S> hasRepoId(long roid) {
    return hasValue(roid, actual.getRepoId(), "getRepoId()");
  }

  public And<S> hasNoHistoryEntries() {
    if (!getHistoryEntries().isEmpty()) {
      failWithActual(simpleFact("expected to have no history entries"));
    }
    return andChainer();
  }

  public And<S> hasNumHistoryEntries(int num) {
    check("getHistoryEntries()").that(getHistoryEntries()).hasSize(num);
    return andChainer();
  }

  public And<S> hasNumHistoryEntriesOfType(HistoryEntry.Type type, int num) {
    List<HistoryEntry> entries = getHistoryEntriesOfType(actual, type);
    check("getHistoryEntriesOfType(%s)", type).that(entries).hasSize(num);
    return andChainer();
  }

  public And<S> hasOneHistoryEntryEachOfTypes(HistoryEntry.Type ... types) {
    hasNumHistoryEntries(types.length);
    for (HistoryEntry.Type type : types) {
      hasNumHistoryEntriesOfType(type, 1);
    }
    return andChainer();
  }

  public And<S> hasOnlyOneHistoryEntry() {
    return hasNumHistoryEntries(1);
  }

  public HistoryEntrySubject hasOnlyOneHistoryEntryWhich() {
    hasOnlyOneHistoryEntry();
    return check("onlyHistoryEntry()").about(historyEntries()).that(getHistoryEntries().get(0));
  }

  // Temporarily suppressing style warning for Truth 0.45 upgrade
  // TODO(weiminyu): Remove after next Truth update
  @SuppressWarnings("UnnecessaryParentheses")
  public Which<HistoryEntrySubject> hasHistoryEntryAtIndex(int index) {
    List<HistoryEntry> historyEntries = getHistoryEntries();
    check("getHistoryEntries().size()").that(historyEntries.size()).isAtLeast(index + 1);
    return new Which<>(
        check("getHistoryEntries(%s)", index)
            .about(historyEntries())
            .that((getHistoryEntries().get(index))));
  }

  public And<S> hasStatusValue(StatusValue statusValue) {
    check("getStatusValues()").that(actual.getStatusValues()).contains(statusValue);
    return andChainer();
  }

  public And<S> doesNotHaveStatusValue(StatusValue statusValue) {
    check("getStatusValues()").that(actual.getStatusValues()).doesNotContain(statusValue);
    return andChainer();
  }

  public And<S> hasExactlyStatusValues(StatusValue... statusValues) {
    if (!ImmutableSet.copyOf(actual.getStatusValues()).equals(ImmutableSet.copyOf(statusValues))) {
      check("getStatusValues()")
          .that(actual.getStatusValues())
          .containsExactly((Object[]) statusValues);
    }
    return andChainer();
  }

  public And<S> hasDeletionTime(DateTime deletionTime) {
    return hasValue(deletionTime, actual.getDeletionTime(), "getDeletionTime()");
  }

  public And<S> hasLastEppUpdateTime(DateTime lastUpdateTime) {
    return hasValue(lastUpdateTime, actual.getLastEppUpdateTime(), "has lastEppUpdateTime");
  }

  public And<S> hasLastEppUpdateTimeAtLeast(DateTime before) {
    DateTime lastEppUpdateTime = actual.getLastEppUpdateTime();
    check("getLastEppUpdateTime()").that(lastEppUpdateTime).isAtLeast(before);
    return andChainer();
  }

  public And<S> hasLastEppUpdateClientId(String clientId) {
    return hasValue(clientId, actual.getLastEppUpdateClientId(), "getLastEppUpdateClientId()");
  }


  public And<S> hasPersistedCurrentSponsorClientId(String clientId) {
    return hasValue(
        clientId,
        actual.getPersistedCurrentSponsorClientId(),
        "getPersistedCurrentSponsorClientId()");
  }

  public And<S> isActiveAt(DateTime time) {
    if (!isActive(actual, time)) {
      failWithActual("expected to be active at", time);
    }
    return andChainer();
  }

  public And<S> isNotActiveAt(DateTime time) {
    if (isActive(actual, time)) {
      failWithActual("expected not to be active at", time);
    }
    return andChainer();
  }

  protected <E> And<S> hasValue(@Nullable E expected, @Nullable E actual, String name) {
    check(name).that(actual).isEqualTo(expected);
    return andChainer();
  }

  protected <E> And<S> doesNotHaveValue(E badValue, E actual, String name) {
    check(name).that(actual).isNotEqualTo(badValue);
    return andChainer();
  }
}
