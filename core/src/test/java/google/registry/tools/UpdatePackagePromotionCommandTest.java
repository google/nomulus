// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.truth.Truth;
import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.PackagePromotion;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableSet;

/** Unit tests for {@link google.registry.tools.UpdatePackagePromotionCommand}. */
public class UpdatePackagePromotionCommandTest
    extends CommandTestCase<UpdatePackagePromotionCommand> {

  @BeforeEach
  void beforeEach() {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.PACKAGE)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setDiscountFraction(1)
                .build());
    PackagePromotion packagePromotion =
        new PackagePromotion.Builder()
            .setToken(token)
            .setMaxDomains(100)
            .setMaxCreates(500)
            .setPackagePrice(Money.of(CurrencyUnit.USD, 1000))
            .setNextBillingDate(DateTime.parse("2012-11-12T05:00:00Z"))
            .setLastNotificationSent(DateTime.parse("2010-11-12T05:00:00Z"))
            .build();
    persistResource(packagePromotion);
  }

  @Test
  void testSuccess() throws Exception {
    runCommandForced(
        "--max_domains=200",
        "--max_creates=1000",
        "--price=USD 2000.00",
        "--next_billing_date=2013-03-17",
        "--clear_last_notification_sent",
        "abc123");

    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    PackagePromotion packagePromotion = packagePromotionOptional.get();
    Truth.assertThat(packagePromotion.getMaxDomains()).isEqualTo(200);
    Truth.assertThat(packagePromotion.getMaxCreates()).isEqualTo(1000);
    Truth.assertThat(packagePromotion.getPackagePrice())
        .isEqualTo(Money.of(CurrencyUnit.USD, 2000));
    Truth.assertThat(packagePromotion.getNextBillingDate())
        .isEqualTo(DateTime.parse("2013-03-17T00:00:00Z"));
    assertThat(packagePromotion.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testFailure_packageDoesNotExist() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("nullPackage")
            .setTokenType(TokenType.PACKAGE)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
            .setAllowedTlds(ImmutableSet.of("foo"))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setDiscountFraction(1)
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--max_domains=100",
                    "--max_creates=500",
                    "--price=USD 1000.00",
                    "--next_billing_date=2012-03-17",
                    "nullPackage"));
    Truth.assertThat(thrown.getMessage())
        .isEqualTo("PackagePromotion with token nullPackage does not exist");
  }

  @Test
  void testSuccess_missingMaxDomains() throws Exception {
    runCommandForced(
        "--max_creates=1000",
        "--price=USD 2000.00",
        "--next_billing_date=2013-03-17",
        "--clear_last_notification_sent",
        "abc123");

    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    PackagePromotion packagePromotion = packagePromotionOptional.get();
    Truth.assertThat(packagePromotion.getMaxDomains()).isEqualTo(100);
    Truth.assertThat(packagePromotion.getMaxCreates()).isEqualTo(1000);
    Truth.assertThat(packagePromotion.getPackagePrice())
        .isEqualTo(Money.of(CurrencyUnit.USD, 2000));
    Truth.assertThat(packagePromotion.getNextBillingDate())
        .isEqualTo(DateTime.parse("2013-03-17T00:00:00Z"));
    assertThat(packagePromotion.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testSuccess_missingNextBillingDate() throws Exception {
    runCommandForced(
        "--max_domains=200",
        "--max_creates=1000",
        "--price=USD 2000.00",
        "--clear_last_notification_sent",
        "abc123");

    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    PackagePromotion packagePromotion = packagePromotionOptional.get();
    Truth.assertThat(packagePromotion.getMaxDomains()).isEqualTo(200);
    Truth.assertThat(packagePromotion.getMaxCreates()).isEqualTo(1000);
    Truth.assertThat(packagePromotion.getPackagePrice())
        .isEqualTo(Money.of(CurrencyUnit.USD, 2000));
    Truth.assertThat(packagePromotion.getNextBillingDate())
        .isEqualTo(DateTime.parse("2012-11-12T05:00:00Z"));
    assertThat(packagePromotion.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testSuccess_missingPrice() throws Exception {
    runCommandForced(
        "--max_domains=200",
        "--max_creates=1000",
        "--next_billing_date=2013-03-17",
        "--clear_last_notification_sent",
        "abc123");

    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    PackagePromotion packagePromotion = packagePromotionOptional.get();
    Truth.assertThat(packagePromotion.getMaxDomains()).isEqualTo(200);
    Truth.assertThat(packagePromotion.getMaxCreates()).isEqualTo(1000);
    Truth.assertThat(packagePromotion.getPackagePrice())
        .isEqualTo(Money.of(CurrencyUnit.USD, 1000));
    Truth.assertThat(packagePromotion.getNextBillingDate())
        .isEqualTo(DateTime.parse("2013-03-17T00:00:00Z"));
    assertThat(packagePromotion.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testSuccess_dontClearLastNotificationSent() throws Exception {
    runCommandForced("--max_domains=200", "--max_creates=1000", "--price=USD 2000.00", "abc123");

    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    PackagePromotion packagePromotion = packagePromotionOptional.get();
    Truth.assertThat(packagePromotion.getMaxDomains()).isEqualTo(200);
    Truth.assertThat(packagePromotion.getMaxCreates()).isEqualTo(1000);
    Truth.assertThat(packagePromotion.getPackagePrice())
        .isEqualTo(Money.of(CurrencyUnit.USD, 2000));
    Truth.assertThat(packagePromotion.getNextBillingDate())
        .isEqualTo(DateTime.parse("2012-11-12T05:00:00Z"));
    Truth.assertThat(packagePromotion.getLastNotificationSent().get())
        .isEqualTo(DateTime.parse("2010-11-12T05:00:00.000Z"));
  }
}
