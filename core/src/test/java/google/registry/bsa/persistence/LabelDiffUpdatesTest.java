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
import static google.registry.bsa.persistence.LabelDiffUpdates.applyLabelDiff;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.tldconfig.idn.IdnTableEnum.UNCONFUSABLE_LATIN;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.bsa.IdnChecker;
import google.registry.bsa.api.BlockLabel;
import google.registry.bsa.api.BlockLabel.LabelType;
import google.registry.bsa.api.UnblockableDomain;
import google.registry.bsa.persistence.BsaUnblockableDomain.Reason;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.ReservationType;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link LabelDiffUpdates}. */
@ExtendWith(MockitoExtension.class)
class LabelDiffUpdatesTest {

  FakeClock fakeClock = new FakeClock(DateTime.parse("2023-11-09T02:08:57.880Z"));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Mock IdnChecker idnChecker;

  @Mock
  DownloadSchedule schedule;

  Tld app;
  Tld dev;
  Tld page;

  @BeforeEach
  void setup() {
    Tld tld = createTld("app");
    tm().transact(
            () ->
                tm().put(
                        tld.asBuilder()
                            .setBsaEnrollStartTime(Optional.of(START_OF_TIME))
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
              tm().insert(new BsaUnblockableDomain("label", "app", Reason.REGISTERED));
            });
    when(idnChecker.getSupportingTlds(any())).thenReturn(ImmutableSet.of(app));

    ImmutableList<UnblockableDomain> unblockableDomains =
        applyLabelDiff(
            ImmutableList.of(BlockLabel.create("label", LabelType.DELETE, ImmutableSet.of())),
            idnChecker,
            schedule,
            fakeClock.nowUtc());
    assertThat(unblockableDomains).isEmpty();
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaLabel.vKey("label")))).isEmpty();
    assertThat(
            tm().transact(() -> tm().loadByKeyIfPresent(BsaUnblockableDomain.vKey("label", "app"))))
        .isEmpty();
  }

  @Test
  void applyLabelDiffs_newAssociationOfLabelToOrder() {
    tm().transact(
            () -> {
              tm().insert(new BsaLabel("label", fakeClock.nowUtc()));
              tm().insert(new BsaUnblockableDomain("label", "app", Reason.REGISTERED));
            });
    when(idnChecker.getSupportingTlds(any())).thenReturn(ImmutableSet.of(app));
    when(idnChecker.getForbiddingTlds(any()))
        .thenReturn(Sets.difference(ImmutableSet.of(dev), ImmutableSet.of()).immutableCopy());

    ImmutableList<UnblockableDomain> unblockableDomains =
        applyLabelDiff(
            ImmutableList.of(
                BlockLabel.create("label", LabelType.NEW_ORDER_ASSOCIATION, ImmutableSet.of())),
            idnChecker,
            schedule,
            fakeClock.nowUtc());
    assertThat(unblockableDomains)
        .containsExactly(
            new UnblockableDomain("label.app", UnblockableDomain.Reason.REGISTERED),
            new UnblockableDomain("label.dev", UnblockableDomain.Reason.INVALID));
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaLabel.vKey("label")))).isPresent();
    assertThat(
            tm().transact(() -> tm().loadByKeyIfPresent(BsaUnblockableDomain.vKey("label", "app"))))
        .isPresent();
  }

  @Test
  void applyLabelDiffs_newLabel() {
    persistActiveDomain("label.app");
    createReservedList("page_reserved", "label", ReservationType.RESERVED_FOR_SPECIFIC_USE);
    addReservedListsToTld("page", ImmutableList.of("page_reserved"));

    when(idnChecker.getForbiddingTlds(any()))
        .thenReturn(Sets.difference(ImmutableSet.of(dev), ImmutableSet.of()).immutableCopy());
    when(idnChecker.getSupportingTlds(any())).thenReturn(ImmutableSet.of(app, page));
    when(schedule.jobCreationTime()).thenReturn(fakeClock.nowUtc());

    ImmutableList<UnblockableDomain> unblockableDomains =
        applyLabelDiff(
            ImmutableList.of(BlockLabel.create("label", LabelType.CREATE, ImmutableSet.of())),
            idnChecker,
            schedule,
            fakeClock.nowUtc());
    assertThat(unblockableDomains)
        .containsExactly(
            new UnblockableDomain("label.app", UnblockableDomain.Reason.REGISTERED),
            new UnblockableDomain("label.page", UnblockableDomain.Reason.RESERVED),
            new UnblockableDomain("label.dev", UnblockableDomain.Reason.INVALID));
    assertThat(tm().transact(() -> tm().loadByKeyIfPresent(BsaLabel.vKey("label")))).isPresent();
    assertThat(
            tm().transact(() -> tm().loadByKey(BsaUnblockableDomain.vKey("label", "app")).reason))
        .isEqualTo(Reason.REGISTERED);
    assertThat(
            tm().transact(() -> tm().loadByKey(BsaUnblockableDomain.vKey("label", "page")).reason))
        .isEqualTo(Reason.RESERVED);
    assertThat(
            tm().transact(() -> tm().loadByKeyIfPresent(BsaUnblockableDomain.vKey("label", "dev"))))
        .isEmpty();
  }
}
