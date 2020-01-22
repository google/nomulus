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

package google.registry.pricing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.pricing.PricingEngineProxy.getDomainCreateCost;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PricingEngineProxy}. */
@RunWith(JUnit4.class)
public class PricingEngineProxyTest {
  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  private Clock clock;

  @Before
  public void before() {
    PremiumList premiumList = persistPremiumList(
        "rich,USD 100",
        "richer,USD 999",
        "fraction,USD 20.50");
    createTld("moka");
    persistResource(Registry.get("moka").asBuilder().setPremiumList(premiumList).build());
    clock = new FakeClock(DateTime.parse("2016-03-17T12:01:00Z"));
  }

  @Test
  public void test_getDomainCreateCost_multipleYears() {
    assertThat(getDomainCreateCost("espresso.moka", clock.nowUtc(), 1))
        .isEqualTo(Money.parse("USD 13"));
    assertThat(getDomainCreateCost("espresso.moka", clock.nowUtc(), 5))
        .isEqualTo(Money.parse("USD 65"));
    assertThat(getDomainCreateCost("fraction.moka", clock.nowUtc(), 1))
        .isEqualTo(Money.parse("USD 20.50"));
    assertThat(getDomainCreateCost("fraction.moka", clock.nowUtc(), 3))
        .isEqualTo(Money.parse("USD 61.50"));
  }

  @Test
  public void test_getDomainRenewCost_multipleYears() {
    assertThat(getDomainRenewCost("espresso.moka", clock.nowUtc(), 1))
        .isEqualTo(Money.parse("USD 11"));
    assertThat(getDomainRenewCost("espresso.moka", clock.nowUtc(), 5))
        .isEqualTo(Money.parse("USD 55"));
    assertThat(getDomainRenewCost("fraction.moka", clock.nowUtc(), 1))
        .isEqualTo(Money.parse("USD 20.50"));
    assertThat(getDomainRenewCost("fraction.moka", clock.nowUtc(), 3))
        .isEqualTo(Money.parse("USD 61.50"));
  }

  @Test
  public void testIsPremiumDomain() {
    createTld("example");
    assertThat(isDomainPremium("poor.example", clock.nowUtc())).isFalse();
    assertThat(isDomainPremium("rich.example", clock.nowUtc())).isTrue();
    assertThat(isDomainPremium("richer.example", clock.nowUtc())).isTrue();
  }

  @Test
  public void testGetDomainCreateCost() {
    // The example tld has a premium price for "rich".
    createTld("example");
    // The default value of 17 is set in createTld().
    assertThat(getDomainCreateCost("poor.example", clock.nowUtc(), 1))
        .isEqualTo(Money.of(USD, 13));
    assertThat(getDomainCreateCost("poor.example", clock.nowUtc(), 2))
        .isEqualTo(Money.of(USD, 26));
    assertThat(getDomainCreateCost("rich.example", clock.nowUtc(), 1))
        .isEqualTo(Money.of(USD, 100));
    assertThat(getDomainCreateCost("rich.example", clock.nowUtc(), 2))
        .isEqualTo(Money.of(USD, 200));
  }

  @Test
  public void testGetDomainRenewCost() {
    // The example tld has a premium price for "rich".
    createTld("example");
    persistResource(
        Registry.get("example")
            .asBuilder()
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, Money.of(USD, 8), clock.nowUtc(), Money.of(USD, 10)))
            .build());
    assertThat(getDomainRenewCost("poor.example", START_OF_TIME, 1))
        .isEqualTo(Money.of(USD, 8));
    assertThat(getDomainRenewCost("poor.example", START_OF_TIME, 2))
        .isEqualTo(Money.of(USD, 16));
    assertThat(getDomainRenewCost("poor.example", clock.nowUtc(), 1))
        .isEqualTo(Money.of(USD, 10));
    assertThat(getDomainRenewCost("poor.example", clock.nowUtc(), 2))
        .isEqualTo(Money.of(USD, 20));
    assertThat(getDomainRenewCost("rich.example", START_OF_TIME, 1))
        .isEqualTo(Money.of(USD, 100));
    assertThat(getDomainRenewCost("rich.example", START_OF_TIME, 2))
        .isEqualTo(Money.of(USD, 200));
    assertThat(getDomainRenewCost("rich.example", clock.nowUtc(), 1))
        .isEqualTo(Money.of(USD, 100));
    assertThat(getDomainRenewCost("rich.example", clock.nowUtc(), 2))
        .isEqualTo(Money.of(USD, 200));
  }

  @Test
  public void testFailure_cantLoadPricingEngine() {
    createTld("example");
    persistResource(
        Registry.get("example")
            .asBuilder()
            .setPremiumPricingEngine("fake")
            .build());
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> getDomainCreateCost("bad.example", clock.nowUtc(), 1));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Could not load pricing engine fake for TLD example");
  }
}
