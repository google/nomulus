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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.billing.BillingEvent.RenewalPriceBehavior;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.PackagePromotion;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableSet;

/** Unit tests for {@link google.registry.tools.CreatePackagePromotionCommand}. */
public class CreatePackagePromotionCommandTest
    extends CommandTestCase<CreatePackagePromotionCommand> {

  @Test
  void testSuccess() throws Exception {
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
    runCommandForced(
        "--max_domains=100",
        "--max_creates=500",
        "--price=USD 1000.00",
        "--next_billing_date=2012-03-17",
        "abc123");

    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    PackagePromotion packagePromotion = packagePromotionOptional.get();
    assertThat(packagePromotion.getMaxDomains()).isEqualTo(100);
    assertThat(packagePromotion.getMaxCreates()).isEqualTo(500);
    assertThat(packagePromotion.getPackagePrice()).isEqualTo(Money.of(CurrencyUnit.USD, 1000));
    assertThat(packagePromotion.getNextBillingDate())
        .isEqualTo(DateTime.parse("2012-03-17T00:00:00Z"));
    assertThat(packagePromotion.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testFailure_tokenIsNotPackageType() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.SINGLE_USE)
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
                    "--next_billing_date=2012-03-17T05:00:00Z",
                    "abc123"));
    assertThat(thrown.getMessage())
        .isEqualTo("The allocation token must be of the PACKAGE token type");
  }

  @Test
  void testFailure_tokenDoesNotExist() throws Exception {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--max_domains=100",
                    "--max_creates=500",
                    "--price=USD 1000.00",
                    "--next_billing_date=2012-03-17T05:00:00Z",
                    "abc123"));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "An allocation token with the token String abc123 does not exist. The package token"
                + " must be created first before it can be used to create a PackagePromotion");
  }

  @Test
  void testFailure_packagePromotionAlreadyExists() throws Exception {
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
    runCommandForced(
        "--max_domains=100",
        "--max_creates=500",
        "--price=USD 1000.00",
        "--next_billing_date=2012-03-17T05:00:00Z",
        "abc123");
    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--max_domains=100",
                    "--max_creates=500",
                    "--price=USD 1000.00",
                    "--next_billing_date=2012-03-17T05:00:00Z",
                    "abc123"));
    assertThat(thrown.getMessage()).isEqualTo("PackagePromotion with token abc123 already exists");
  }

  @Test
  void testSuccess_missingMaxDomainsAndCreatesInitializesToZero() throws Exception {
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
    runCommandForced("--price=USD 1000.00", "--next_billing_date=2012-03-17", "abc123");
    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    PackagePromotion packagePromotion = packagePromotionOptional.get();
    assertThat(packagePromotion.getMaxDomains()).isEqualTo(0);
    assertThat(packagePromotion.getMaxCreates()).isEqualTo(0);
    assertThat(packagePromotion.getPackagePrice()).isEqualTo(Money.of(CurrencyUnit.USD, 1000));
    assertThat(packagePromotion.getNextBillingDate())
        .isEqualTo(DateTime.parse("2012-03-17T00:00:00Z"));
    assertThat(packagePromotion.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testSuccess_missingNextBillingDateInitializesToEndOfTime() throws Exception {
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
    runCommandForced("--max_domains=100", "--max_creates=500", "--price=USD 1000.00", "abc123");

    Optional<PackagePromotion> packagePromotionOptional =
        PackagePromotion.loadByTokenString("abc123");
    assertThat(packagePromotionOptional).isPresent();
    PackagePromotion packagePromotion = packagePromotionOptional.get();
    assertThat(packagePromotion.getMaxDomains()).isEqualTo(100);
    assertThat(packagePromotion.getMaxCreates()).isEqualTo(500);
    assertThat(packagePromotion.getPackagePrice()).isEqualTo(Money.of(CurrencyUnit.USD, 1000));
    assertThat(packagePromotion.getNextBillingDate()).isEqualTo(END_OF_TIME);
    assertThat(packagePromotion.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testFailure_missingPrice() throws Exception {
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
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--max_domains=100",
                    "--max_creates=500",
                    "--next_billing_date=2012-03-17T05:00:00Z",
                    "abc123"));
    assertThat(thrown.getMessage())
        .isEqualTo("PackagePrice is required when creating a new package");
  }
}
