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

package google.registry.model.tld;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.tld.TldSettings.toYaml;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TestDataHelper;
import google.registry.tldconfig.idn.IdnTableEnum;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TldSettings}. */
public class TldSettingsTest {

  private final FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @Test
  void testSuccess_roundTripTldToTldSettingsAndBack() {
    Tld oldTld = createTld("tld");
    TldSettings tldSettings = new TldSettings().fromTld(oldTld);
    Tld newTld = tm().transact(() -> tldSettings.toTld());
    assertThat(oldTld).isEqualTo(newTld);
  }

  @Test
  void testSuccess_tldToYaml() {
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    Tld tld =
        createTld("tld")
            .asBuilder()
            .setDnsAPlusAaaaTtl(Duration.standardHours(1))
            .setDnsWriters(ImmutableSet.of("baz", "bang"))
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    DateTime.parse("2000-06-01T00:00:00Z"),
                    Money.of(USD, 100),
                    DateTime.parse("2000-06-02T00:00:00Z"),
                    Money.of(USD, 0)))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("foo"))
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .setIdnTables(ImmutableSet.of(IdnTableEnum.JA, IdnTableEnum.EXTENDED_LATIN))
            .build();
    assertThat(toYaml(tld)).isEqualTo(TestDataHelper.loadFile(getClass(), "tld.yaml"));
  }
}
