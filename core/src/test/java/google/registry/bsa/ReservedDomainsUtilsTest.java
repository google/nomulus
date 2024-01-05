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

package google.registry.bsa;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.ReservedDomainsUtils.getAllReservedDomainsInTld;
import static google.registry.model.tld.Tld.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.model.tld.label.ReservationType.ALLOWED_IN_SUNRISE;
import static google.registry.model.tld.label.ReservationType.FULLY_BLOCKED;
import static google.registry.model.tld.label.ReservationType.NAME_COLLISION;
import static google.registry.model.tld.label.ReservationType.RESERVED_FOR_ANCHOR_TENANT;
import static google.registry.model.tld.label.ReservationType.RESERVED_FOR_SPECIFIC_USE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.ReservedList;
import google.registry.model.tld.label.ReservedList.ReservedListEntry;
import google.registry.model.tld.label.ReservedListDao;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ReservedDomainsUtils}. */
class ReservedDomainsUtilsTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @BeforeEach
  void setup() {
    ImmutableMap<String, ReservedListEntry> byType =
        ImmutableMap.of(
            "sunrise",
            ReservedListEntry.create("sunrise", ALLOWED_IN_SUNRISE, ""),
            "specific",
            ReservedListEntry.create("specific", RESERVED_FOR_SPECIFIC_USE, ""),
            "anchor",
            ReservedListEntry.create("anchor", RESERVED_FOR_ANCHOR_TENANT, ""),
            "fully",
            ReservedListEntry.create("fully", FULLY_BLOCKED, ""),
            "name",
            ReservedListEntry.create("name", NAME_COLLISION, ""));

    ImmutableMap<String, ReservedListEntry> altList =
        ImmutableMap.of(
            "anchor",
            ReservedListEntry.create("anchor", RESERVED_FOR_ANCHOR_TENANT, ""),
            "somethingelse",
            ReservedListEntry.create("somethingelse", RESERVED_FOR_ANCHOR_TENANT, ""));

    ReservedListDao.save(
        new ReservedList.Builder()
            .setName("testlist")
            .setCreationTimestamp(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(byType)
            .build());

    ReservedListDao.save(
        new ReservedList.Builder()
            .setName("testlist2")
            .setCreationTimestamp(fakeClock.nowUtc())
            .setShouldPublish(false)
            .setReservedListMap(altList)
            .build());

    createTld("tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    fakeClock.nowUtc(), START_DATE_SUNRISE,
                    fakeClock.nowUtc().plusMillis(1), GENERAL_AVAILABILITY))
            .setReservedListsByName(ImmutableSet.of("testlist"))
            .build());

    createTld("tld2");
    persistResource(
        Tld.get("tld2")
            .asBuilder()
            .setReservedListsByName(ImmutableSet.of("testlist", "testlist2"))
            .build());
  }

  @Test
  void enumerateReservedDomain_in_sunrise() {
    assertThat(getAllReservedDomainsInTld(Tld.get("tld"), fakeClock.nowUtc()))
        .containsExactly("specific.tld", "anchor.tld", "fully.tld");
  }

  @Test
  void enumerateReservedDomain_after_sunrise() {
    fakeClock.advanceOneMilli();
    assertThat(getAllReservedDomainsInTld(Tld.get("tld"), fakeClock.nowUtc()))
        .containsExactly("sunrise.tld", "name.tld", "specific.tld", "anchor.tld", "fully.tld");
  }

  @Test
  void enumerateReservedDomain_multiple_lists() {
    assertThat(getAllReservedDomainsInTld(Tld.get("tld2"), fakeClock.nowUtc()))
        .containsExactly(
            "somethingelse.tld2",
            "sunrise.tld2",
            "name.tld2",
            "specific.tld2",
            "anchor.tld2",
            "fully.tld2");
  }
}
