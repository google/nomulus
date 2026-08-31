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

package google.registry.flows.domain;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.domain.DomainFlowUtils.checkHasBillingAccount;
import static google.registry.flows.domain.DomainFlowUtils.isDomainEligibleForXap;
import static google.registry.flows.domain.DomainFlowUtils.loadDomainIfInXap;
import static google.registry.flows.domain.DomainFlowUtils.wasDeletedDuringAddGracePeriod;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.minusDays;
import static org.joda.money.CurrencyUnit.CHF;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.MissingBillingAccountMapException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.persistence.transaction.JpaTransactionManagerExtension;
import java.time.Duration;
import java.time.Instant;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainFlowUtils}. */
class DomainFlowUtilsTest extends ResourceFlowTestCase<DomainInfoFlow, Domain> {

  @BeforeEach
  void setup() {
    setEppInput("domain_info.xml");
    createTld("tld");
    persistResource(JpaTransactionManagerExtension.makeRegistrar1().asBuilder().build());
  }

  @Test
  void testValidateDomainNameAcceptsValidName() throws EppException {
    assertThat(DomainFlowUtils.validateDomainName("example.tld")).isNotNull();
  }

  @Test
  void testValidateDomainName_IllegalCharacters() {
    BadDomainNameCharacterException thrown =
        assertThrows(
            BadDomainNameCharacterException.class,
            () -> DomainFlowUtils.validateDomainName("$.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain names can only contain a-z, 0-9, '.' and '-'");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_DomainNameWithEmptyParts() {
    EmptyDomainNamePartException thrown =
        assertThrows(
            EmptyDomainNamePartException.class,
            () -> DomainFlowUtils.validateDomainName("example."));
    assertThat(thrown).hasMessageThat().isEqualTo("No part of a domain name can be empty");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_DomainNameWithLessThanTwoParts() {
    BadDomainNamePartsCountException thrown =
        assertThrows(
            BadDomainNamePartsCountException.class,
            () -> DomainFlowUtils.validateDomainName("example"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name must have exactly one part above the TLD");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_invalidTLD() {
    TldDoesNotExistException thrown =
        assertThrows(
            TldDoesNotExistException.class,
            () -> DomainFlowUtils.validateDomainName("example.nosuchtld"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name is under tld nosuchtld which doesn't exist");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_DomainNameIsTooLong() {
    DomainLabelTooLongException thrown =
        assertThrows(
            DomainLabelTooLongException.class,
            () ->
                DomainFlowUtils.validateDomainName(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain labels cannot be longer than 63 characters");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_leadingDash() {
    LeadingDashException thrown =
        assertThrows(
            LeadingDashException.class, () -> DomainFlowUtils.validateDomainName("-example.foo"));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain labels cannot begin with a dash");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_trailingDash() {
    TrailingDashException thrown =
        assertThrows(
            TrailingDashException.class, () -> DomainFlowUtils.validateDomainName("example-.foo"));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain labels cannot end with a dash");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_invalidIDN() {
    InvalidPunycodeException thrown =
        assertThrows(
            InvalidPunycodeException.class,
            () -> DomainFlowUtils.validateDomainName("xn--abcd.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Domain name starts with xn-- but is not a valid IDN");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testValidateDomainName_containsInvalidDashes() {
    DashesInThirdAndFourthException thrown =
        assertThrows(
            DashesInThirdAndFourthException.class,
            () -> DomainFlowUtils.validateDomainName("ab--cd.foo"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Non-IDN domain names cannot contain dashes in the third or fourth position");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testCheckHasBillingAccount_ignoresTestTlds() throws EppException {
    persistFoobarTld(TldType.TEST);
    checkHasBillingAccount("TheRegistrar", "foobar");
  }

  @Test
  void testCheckHasBillingAccount_failsOnRealTld() {
    persistFoobarTld(TldType.REAL);
    MissingBillingAccountMapException thrown =
        assertThrows(
            MissingBillingAccountMapException.class,
            () -> checkHasBillingAccount("TheRegistrar", "foobar"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Registrar is not fully onboarded for TLDs that bill in CHF");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void persistFoobarTld(TldType tldType) {
    persistResource(
        newTld("foobar", "FOOBAR")
            .asBuilder()
            .setTldType(tldType)
            .setCurrency(CHF)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_INSTANT, Money.ofMajor(CHF, 800)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_INSTANT, Money.ofMajor(CHF, 800)))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(START_INSTANT, Money.ofMajor(CHF, 800)))
            .setRegistryLockOrUnlockBillingCost(Money.ofMajor(CHF, 800))
            .setServerStatusChangeBillingCost(Money.ofMajor(CHF, 800))
            .setRestoreBillingCost(Money.ofMajor(CHF, 800))
            .build());
  }

  @Test
  void testIsDomainEligibleForXap_activeDomain_returnsFalse() {
    Domain domain = persistActiveDomain("active.tld");
    assertThat(isDomainEligibleForXap(domain, Tld.get("tld"), clock.now())).isFalse();
  }

  @Test
  void testIsDomainEligibleForXap_deletedOutsideAgp_returnsTrue() {
    Domain domain = persistDeletedDomain("deleted.tld", minusDays(clock.now(), 1));
    assertThat(isDomainEligibleForXap(domain, Tld.get("tld"), clock.now())).isTrue();
  }

  @Test
  void testIsDomainEligibleForXap_deletedDuringAgp_returnsFalse() {
    Domain domain =
        persistActiveDomain("agp.tld")
            .asBuilder()
            .setCreationTimeForTest(minusDays(clock.now(), 2))
            .setDeletionTime(minusDays(clock.now(), 1))
            .build();
    persistResource(domain);
    assertThat(isDomainEligibleForXap(domain, Tld.get("tld"), clock.now())).isFalse();
  }

  @Test
  void testLoadDomainIfInXap_eligibleAndWithinWindow_returnsDomain() {
    Domain domain = persistDeletedDomain("xap.tld", minusDays(clock.now(), 2));
    assertThat(loadDomainIfInXap("xap.tld", clock.now(), Duration.ofDays(10))).hasValue(domain);
  }

  @Test
  void testLoadDomainIfInXap_deletedOutsideWindow_returnsEmpty() {
    persistDeletedDomain("expired.tld", minusDays(clock.now(), 15));
    assertThat(loadDomainIfInXap("expired.tld", clock.now(), Duration.ofDays(10))).isEmpty();
  }

  @Test
  void testLoadDomainIfInXap_deletedDuringAgp_returnsEmpty() {
    Domain domain =
        persistActiveDomain("agp.tld")
            .asBuilder()
            .setCreationTimeForTest(minusDays(clock.now(), 2))
            .setDeletionTime(minusDays(clock.now(), 1))
            .build();
    persistResource(domain);
    assertThat(loadDomainIfInXap("agp.tld", clock.now(), Duration.ofDays(10))).isEmpty();
  }

  @Test
  void testIsDomainEligibleForXap_deletedAtNow_returnsTrue() {
    Domain domain = persistDeletedDomain("deleted-now.tld", clock.now());
    assertThat(isDomainEligibleForXap(domain, Tld.get("tld"), clock.now())).isTrue();
  }

  @Test
  void testIsDomainEligibleForXap_deletedInFuture_returnsFalse() {
    Domain domain = persistDeletedDomain("future.tld", clock.now().plus(Duration.ofDays(1)));
    assertThat(isDomainEligibleForXap(domain, Tld.get("tld"), clock.now())).isFalse();
  }

  @Test
  void testIsDomainEligibleForXap_agpBoundaryExact_returnsFalse() {
    Tld tld = Tld.get("tld");
    Domain domain =
        persistActiveDomain("agp-exact.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.now().minus(tld.getAddGracePeriodLength()))
            .setDeletionTime(clock.now())
            .build();
    persistResource(domain);
    assertThat(isDomainEligibleForXap(domain, tld, clock.now())).isFalse();
  }

  @Test
  void testIsDomainEligibleForXap_deletedJustAfterAgp_returnsTrue() {
    Tld tld = Tld.get("tld");
    Domain domain =
        persistActiveDomain("agp-after.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.now().minus(tld.getAddGracePeriodLength()).minusMillis(1))
            .setDeletionTime(clock.now())
            .build();
    persistResource(domain);
    assertThat(isDomainEligibleForXap(domain, tld, clock.now())).isTrue();
  }

  @Test
  void testWasDeletedDuringAddGracePeriod_boundaries() {
    Tld tld = Tld.get("tld");
    Domain domainExact =
        persistActiveDomain("agp-exact-fn.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.now().minus(tld.getAddGracePeriodLength()))
            .setDeletionTime(clock.now())
            .build();
    persistResource(domainExact);
    assertThat(wasDeletedDuringAddGracePeriod(domainExact, tld)).isTrue();

    Domain domainAfter =
        persistActiveDomain("agp-after-fn.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.now().minus(tld.getAddGracePeriodLength()).minusMillis(1))
            .setDeletionTime(clock.now())
            .build();
    persistResource(domainAfter);
    assertThat(wasDeletedDuringAddGracePeriod(domainAfter, tld)).isFalse();
  }

  @Test
  void testLoadDomainIfInXap_deletedAtNow_returnsDomain() {
    Domain domain = persistDeletedDomain("xap-now.tld", clock.now());
    assertThat(loadDomainIfInXap("xap-now.tld", clock.now(), Duration.ofDays(10))).hasValue(domain);
  }

  @Test
  void testLoadDomainIfInXap_exactWindowBoundary_returnsEmpty() {
    persistDeletedDomain("expired-exact.tld", minusDays(clock.now(), 10));
    assertThat(loadDomainIfInXap("expired-exact.tld", clock.now(), Duration.ofDays(10))).isEmpty();
  }

  @Test
  void testLoadDomainIfInXap_justInsideWindowBoundary_returnsDomain() {
    Domain domain =
        persistDeletedDomain("inside-window.tld", minusDays(clock.now(), 10).plusSeconds(1));
    assertThat(loadDomainIfInXap("inside-window.tld", clock.now(), Duration.ofDays(10)))
        .hasValue(domain);
  }

  @Test
  void testLoadDomainIfInXap_justOutsideWindowBoundary_returnsEmpty() {
    persistDeletedDomain("outside-window.tld", minusDays(clock.now(), 10).minusSeconds(1));
    assertThat(loadDomainIfInXap("outside-window.tld", clock.now(), Duration.ofDays(10))).isEmpty();
  }

  @Test
  void testIsDomainEligibleForXap_deletedOneMilliInFuture_returnsFalse() {
    Instant now = clock.now();
    Domain domain = persistDeletedDomain("future-milli.tld", now.plusMillis(1));
    assertThat(isDomainEligibleForXap(domain, Tld.get("tld"), now)).isFalse();
  }

  @Test
  void testLoadDomainIfInXap_oneMilliInsideWindow_returnsDomain() {
    Domain domain = persistDeletedDomain("inside-milli.tld", clock.now());
    Instant queryNow = domain.getDeletionTime().plus(Duration.ofDays(10)).minusMillis(1);
    assertThat(loadDomainIfInXap("inside-milli.tld", queryNow, Duration.ofDays(10)))
        .hasValue(domain);
  }

  @Test
  void testLoadDomainIfInXap_oneMilliOutsideWindow_returnsEmpty() {
    Domain domain = persistDeletedDomain("outside-milli.tld", clock.now());
    Instant queryNow = domain.getDeletionTime().plus(Duration.ofDays(10)).plusMillis(1);
    assertThat(loadDomainIfInXap("outside-milli.tld", queryNow, Duration.ofDays(10))).isEmpty();
  }

  @Test
  void testLoadDomainIfInXap_exactAgpBoundary_returnsEmpty() {
    Tld tld = Tld.get("tld");
    Domain domain =
        persistActiveDomain("agp-exact-load.tld")
            .asBuilder()
            .setCreationTimeForTest(clock.now().minus(tld.getAddGracePeriodLength()))
            .setDeletionTime(clock.now())
            .build();
    persistResource(domain);
    assertThat(loadDomainIfInXap("agp-exact-load.tld", clock.now(), Duration.ofDays(10))).isEmpty();
  }

  @Test
  void testLoadDomainIfInXap_oneMilliAfterAgpBoundary_returnsDomain() {
    Tld tld = Tld.get("tld");
    Domain domain =
        persistResource(
            persistActiveDomain("agp-after-load.tld")
                .asBuilder()
                .setCreationTimeForTest(
                    clock.now().minus(tld.getAddGracePeriodLength()).minusMillis(1))
                .setDeletionTime(clock.now())
                .build());
    assertThat(loadDomainIfInXap("agp-after-load.tld", clock.now(), Duration.ofDays(10)))
        .hasValue(domain);
  }
}
