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

package google.registry.bsa.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.ReservedDomainsTestingUtils.addReservedListsToTld;
import static google.registry.bsa.ReservedDomainsTestingUtils.createReservedList;
import static google.registry.bsa.persistence.BsaTestingUtils.persistBsaLabel;
import static google.registry.model.tld.label.ReservationType.RESERVED_FOR_SPECIFIC_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.api.UnblockableDomainChange;
import google.registry.bsa.persistence.BsaUnblockableDomain.Reason;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainsRefresher}. */
public class DomainsRefresherTest {

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  DomainsRefresher refresher;

  @BeforeEach
  void setup() {
    createTld("tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setBsaEnrollStartTime(Optional.of(fakeClock.nowUtc().minusMillis(1)))
            .build());
    refresher = new DomainsRefresher(START_OF_TIME, fakeClock.nowUtc(), Duration.ZERO, 100);
  }

  @Test
  void registeredUnblockable_removed_afterDomainIsDeleted() {
    persistBsaLabel("label");
    tm().transact(() -> tm().insert(BsaUnblockableDomain.of("label.tld", Reason.REGISTERED)));
    assertThat(refresher.refreshStaleUnblockables())
        .containsExactly(
            UnblockableDomainChange.createDeleted(
                new UnblockableDomain("label.tld", UnblockableDomain.Reason.REGISTERED)));
  }

  @Test
  void reservedUnblockable_removed_whenReservedLabelIsRemoved() {
    persistBsaLabel("label");
    tm().transact(() -> tm().insert(BsaUnblockableDomain.of("label.tld", Reason.RESERVED)));
    assertThat(refresher.refreshStaleUnblockables())
        .containsExactly(
            UnblockableDomainChange.createDeleted(
                new UnblockableDomain("label.tld", UnblockableDomain.Reason.RESERVED)));
  }

  @Test
  void regsiteredUnblockable_added_whenDomainIsAdded() {
    persistResource(newDomain("label.tld"));
    persistBsaLabel("label");
    assertThat(refresher.getNewUnblockables())
        .containsExactly(
            UnblockableDomainChange.createNew(
                new UnblockableDomain("label.tld", UnblockableDomain.Reason.REGISTERED)));
  }

  @Test
  void reservedUnblockable_added_whenReservedLabelIsAdded() {
    persistBsaLabel("label");
    createReservedList("reservedList", "label", RESERVED_FOR_SPECIFIC_USE);
    addReservedListsToTld("tld", ImmutableList.of("reservedList"));
    assertThat(refresher.getNewUnblockables())
        .containsExactly(
            UnblockableDomainChange.createNew(
                new UnblockableDomain("label.tld", UnblockableDomain.Reason.RESERVED)));
  }

  @Test
  void registeredUnblockable_changedToReserved_whenDomainIsDeletedButLabelIsReserved() {
    persistBsaLabel("label");
    createReservedList("reservedList", "label", RESERVED_FOR_SPECIFIC_USE);
    addReservedListsToTld("tld", ImmutableList.of("reservedList"));
    tm().transact(() -> tm().insert(BsaUnblockableDomain.of("label.tld", Reason.REGISTERED)));

    assertThat(refresher.refreshStaleUnblockables())
        .containsExactly(
            UnblockableDomainChange.createChanged(
                new UnblockableDomain("label.tld", UnblockableDomain.Reason.REGISTERED),
                UnblockableDomain.Reason.RESERVED));
  }

  @Test
  void reservedUnblockableUpgraded_changedToRegistered_whenDomainIsCreatedButNoLongerReserved() {
    persistBsaLabel("label");
    tm().transact(() -> tm().insert(BsaUnblockableDomain.of("label.tld", Reason.RESERVED)));

    persistResource(newDomain("label.tld"));
    assertThat(refresher.refreshStaleUnblockables())
        .containsExactly(
            UnblockableDomainChange.createChanged(
                new UnblockableDomain("label.tld", UnblockableDomain.Reason.RESERVED),
                UnblockableDomain.Reason.REGISTERED));
  }

  @Test
  void reservedUnblockableUpgraded_changedToRegistered_whenDomainIsCreatedAndStillReserved() {
    persistBsaLabel("label");
    createReservedList("reservedList", "label", RESERVED_FOR_SPECIFIC_USE);
    addReservedListsToTld("tld", ImmutableList.of("reservedList"));
    tm().transact(() -> tm().insert(BsaUnblockableDomain.of("label.tld", Reason.RESERVED)));

    persistResource(newDomain("label.tld"));
    assertThat(refresher.refreshStaleUnblockables())
        .containsExactly(
            UnblockableDomainChange.createChanged(
                new UnblockableDomain("label.tld", UnblockableDomain.Reason.RESERVED),
                UnblockableDomain.Reason.REGISTERED));
  }
}
