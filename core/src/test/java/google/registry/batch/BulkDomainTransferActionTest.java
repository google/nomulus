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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.flows.DaggerEppTestComponent;
import google.registry.flows.EppController;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import google.registry.util.DateTimeUtils;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link BulkDomainTransferAction}. */
public class BulkDomainTransferActionTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2024-01-01T00:00:00.000Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  private final FakeResponse response = new FakeResponse();
  private final RateLimiter rateLimiter = mock(RateLimiter.class);

  private Domain activeDomain;
  private Domain alreadyTransferredDomain;
  private Domain pendingDeleteDomain;
  private Domain deletedDomain;

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("tld");
    DateTime now = fakeClock.nowUtc();
    // The default registrar is TheRegistrar, which will be the losing registrar
    activeDomain =
        persistDomainWithDependentResources(
            "active", "tld", null, now, now.minusDays(1), DateTimeUtils.END_OF_TIME);
    alreadyTransferredDomain =
        persistResource(
            persistDomainWithDependentResources(
                    "alreadytransferred",
                    "tld",
                    null,
                    now,
                    now.minusDays(1),
                    DateTimeUtils.END_OF_TIME)
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .build());
    pendingDeleteDomain =
        persistResource(
            persistDomainWithDependentResources(
                    "pendingdelete", "tld", null, now, now.minusDays(1), now.plusMonths(1))
                .asBuilder()
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
                .build());
    deletedDomain = persistDeletedDomain("deleted.tld", now.minusMonths(1));
  }

  @Test
  void testSuccess_normalRun() {
    assertThat(activeDomain.getCurrentSponsorRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(alreadyTransferredDomain.getCurrentSponsorRegistrarId()).isEqualTo("NewRegistrar");
    assertThat(pendingDeleteDomain.getCurrentSponsorRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(deletedDomain.getCurrentSponsorRegistrarId()).isEqualTo("TheRegistrar");
    DateTime preRunTime = fakeClock.nowUtc();

    BulkDomainTransferAction action =
        createAction("active.tld", "alreadytransferred.tld", "pendingdelete.tld", "deleted.tld");
    fakeClock.advanceOneMilli();

    DateTime runTime = fakeClock.nowUtc();
    action.run();

    fakeClock.advanceOneMilli();
    DateTime now = fakeClock.nowUtc();

    // The active domain should have a new update timestamp and current registrar
    // The cloneProjectedAtTime calls are necessary to resolve the transfers, even though the
    // transfers have a time period of 0
    activeDomain = loadByEntity(activeDomain);
    assertThat(activeDomain.cloneProjectedAtTime(now).getCurrentSponsorRegistrarId())
        .isEqualTo("NewRegistrar");
    assertThat(activeDomain.getUpdateTimestamp().getTimestamp()).isEqualTo(runTime);

    // The other three domains shouldn't change
    alreadyTransferredDomain = loadByEntity(alreadyTransferredDomain);
    assertThat(alreadyTransferredDomain.cloneProjectedAtTime(now).getCurrentSponsorRegistrarId())
        .isEqualTo("NewRegistrar");
    assertThat(alreadyTransferredDomain.getUpdateTimestamp().getTimestamp()).isEqualTo(preRunTime);

    pendingDeleteDomain = loadByEntity(pendingDeleteDomain);
    assertThat(pendingDeleteDomain.cloneProjectedAtTime(now).getCurrentSponsorRegistrarId())
        .isEqualTo("TheRegistrar");
    assertThat(pendingDeleteDomain.getUpdateTimestamp().getTimestamp()).isEqualTo(preRunTime);

    deletedDomain = loadByEntity(deletedDomain);
    assertThat(deletedDomain.cloneProjectedAtTime(now).getCurrentSponsorRegistrarId())
        .isEqualTo("TheRegistrar");
    assertThat(deletedDomain.getUpdateTimestamp().getTimestamp()).isEqualTo(preRunTime);
  }

  private BulkDomainTransferAction createAction(String... domains) {
    EppController eppController =
        DaggerEppTestComponent.builder()
            .fakesAndMocksModule(FakesAndMocksModule.create(new FakeClock()))
            .build()
            .startRequest()
            .eppController();
    return new BulkDomainTransferAction(
        eppController,
        new FakeLockHandler(true),
        rateLimiter,
        ImmutableList.copyOf(domains),
        "NewRegistrar",
        "TheRegistrar",
        true,
        "reason",
        response);
  }
}
