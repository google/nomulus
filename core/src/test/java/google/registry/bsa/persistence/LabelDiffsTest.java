// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.bsa.persistence.LabelDiffs.applyLabelDiff;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.tldconfig.idn.IdnTableEnum.UNCONFUSABLE_LATIN;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.bsa.IdnChecker;
import google.registry.bsa.api.Label;
import google.registry.bsa.api.Label.LabelType;
import google.registry.bsa.api.NonBlockedDomain;
import google.registry.bsa.persistence.BsaDomainInUse.Reason;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.ReservationType;
import google.registry.model.tld.label.ReservedList;
import google.registry.model.tld.label.ReservedList.ReservedListEntry;
import google.registry.model.tld.label.ReservedListDao;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link LabelDiffs}. */
@ExtendWith(MockitoExtension.class)
class LabelDiffsTest {

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Mock IdnChecker idnChecker;
  @Mock DownloadSchedule schedule;

  Tld app;
  Tld dev;
  Tld page;

  @BeforeEach
  void setupTld() {
    Tld tld = createTld("app");
    tm().transact(
            () ->
                tm().put(
                        tld.asBuilder()
                            .setBsaEnrollStartTime(START_OF_TIME)
                            .setIdnTables(ImmutableSet.of(UNCONFUSABLE_LATIN))
                            .build()));
    app = tm().transact(() -> tm().loadByEntity(tld));
    dev = createTld("dev");
    page = createTld("page");
  }

  @Test
  void applyLabelDiffs_delete() {
    tm().transact(
            () -> {
              tm().insert(new BsaLabel("label", fakeClock.nowUtc()));
              tm().insert(new BsaDomainInUse("label", "app", Reason.REGISTERED));
            });

    ImmutableList<NonBlockedDomain> nonBlockedDomains =
        applyLabelDiff(
            ImmutableList.of(Label.of("label", LabelType.DELETE, ImmutableSet.of())),
            idnChecker,
            schedule,
            fakeClock.nowUtc());
    assertThat(nonBlockedDomains).isEmpty();
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaLabel.vKey("label")))).isEmpty();
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaDomainInUse.vKey("label", "app"))))
        .isEmpty();
  }

  @Test
  void applyLabelDiffs_newAssociationOfLabelToOrder() {
    tm().transact(
            () -> {
              tm().insert(new BsaLabel("label", fakeClock.nowUtc()));
              tm().insert(new BsaDomainInUse("label", "app", Reason.REGISTERED));
            });
    when(idnChecker.getForbiddingTlds(any()))
        .thenReturn(Sets.difference(ImmutableSet.of(dev), ImmutableSet.of()));

    ImmutableList<NonBlockedDomain> nonBlockedDomains =
        applyLabelDiff(
            ImmutableList.of(Label.of("label", LabelType.NEW_ORDER_ASSOCIATION, ImmutableSet.of())),
            idnChecker,
            schedule,
            fakeClock.nowUtc());
    assertThat(nonBlockedDomains)
        .containsExactly(
            NonBlockedDomain.of("label.app", NonBlockedDomain.Reason.REGISTERED),
            NonBlockedDomain.of("label.dev", NonBlockedDomain.Reason.INVALID));
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaLabel.vKey("label")))).isPresent();
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaDomainInUse.vKey("label", "app"))))
        .isPresent();
  }

  @Test
  void applyLabelDiffs_newLabel() {
    persistActiveDomain("label.app");
    ReservedListDao.save(
        new ReservedList.Builder()
            .setReservedListMap(
                ImmutableMap.of(
                    "label",
                    ReservedListEntry.create(
                        "label", ReservationType.RESERVED_FOR_SPECIFIC_USE, null)))
            .setName("page_reserved")
            .setCreationTimestamp(fakeClock.nowUtc())
            .build());
    ReservedList reservedList = ReservedList.get("page_reserved").get();
    tm().transact(() -> tm().put(page.asBuilder().setReservedLists(reservedList).build()));

    when(idnChecker.getForbiddingTlds(any()))
        .thenReturn(Sets.difference(ImmutableSet.of(dev), ImmutableSet.of()));
    when(idnChecker.getSupportingTlds(any())).thenReturn(ImmutableSet.of(app, page));
    when(schedule.jobCreationTime()).thenReturn(fakeClock.nowUtc());

    ImmutableList<NonBlockedDomain> nonBlockedDomains =
        applyLabelDiff(
            ImmutableList.of(Label.of("label", LabelType.CREATE, ImmutableSet.of())),
            idnChecker,
            schedule,
            fakeClock.nowUtc());
    assertThat(nonBlockedDomains)
        .containsExactly(
            NonBlockedDomain.of("label.app", NonBlockedDomain.Reason.REGISTERED),
            NonBlockedDomain.of("label.page", NonBlockedDomain.Reason.RESERVED),
            NonBlockedDomain.of("label.dev", NonBlockedDomain.Reason.INVALID));
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaLabel.vKey("label")))).isPresent();
    assertThat(tm().transact(() -> tm().loadByKey(BsaDomainInUse.vKey("label", "app")).reason))
        .isEqualTo(Reason.REGISTERED);
    assertThat(tm().transact(() -> tm().loadByKey(BsaDomainInUse.vKey("label", "page")).reason))
        .isEqualTo(Reason.RESERVED);
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaDomainInUse.vKey("label", "dev"))))
        .isEmpty();
  }
}
