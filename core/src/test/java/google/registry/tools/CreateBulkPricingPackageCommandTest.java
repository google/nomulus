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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.BulkPricingPackage;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CreateBulkPricingPackageCommand}. */
public class CreateBulkPricingPackageCommandTest
    extends CommandTestCase<CreateBulkPricingPackageCommand> {

  @Test
  void testSuccess() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.BULK_PRICING)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
            .setAllowedTlds(ImmutableSet.of("foo"))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, 0))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setDiscountFraction(1.0)
            .build());
    runCommandForced(
        "--max_domains=100",
        "--max_creates=500",
        "--price=USD 1000.00",
        "--next_billing_date=2012-03-17T00:00:00Z",
        "abc123");

    Optional<BulkPricingPackage> bulkPricingPackageOptional =
        tm().transact(() -> BulkPricingPackage.loadByTokenString("abc123"));
    assertThat(bulkPricingPackageOptional).isPresent();
    BulkPricingPackage bulkPricingPackage = bulkPricingPackageOptional.get();
    assertThat(bulkPricingPackage.getMaxDomains()).isEqualTo(100);
    assertThat(bulkPricingPackage.getMaxCreates()).isEqualTo(500);
    assertThat(bulkPricingPackage.getBulkPrice()).isEqualTo(Money.of(CurrencyUnit.USD, 1000));
    assertThat(bulkPricingPackage.getNextBillingDate())
        .isEqualTo(DateTime.parse("2012-03-17T00:00:00Z"));
    assertThat(bulkPricingPackage.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testFailure_tokenIsNotBulkType() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.SINGLE_USE)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
            .setAllowedTlds(ImmutableSet.of("foo"))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, 0))
            .setDiscountFraction(1.0)
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
        .isEqualTo("The allocation token must be of the BULK_PRICING token type");
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
            "An allocation token with the token String abc123 does not exist. The bulk token"
                + " must be created first before it can be used to create a BulkPricingPackage");
  }

  @Test
  void testFailure_bulkPricingPackageAlreadyExists() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.BULK_PRICING)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
            .setAllowedTlds(ImmutableSet.of("foo"))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, 0))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setDiscountFraction(1.0)
            .build());
    runCommandForced(
        "--max_domains=100",
        "--max_creates=500",
        "--price=USD 1000.00",
        "--next_billing_date=2012-03-17T05:00:00Z",
        "abc123");
    Optional<BulkPricingPackage> bulkPricingPackageOptional =
        tm().transact(() -> BulkPricingPackage.loadByTokenString("abc123"));
    assertThat(bulkPricingPackageOptional).isPresent();
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
        .isEqualTo("BulkPricingPackage with token abc123 already exists");
  }

  @Test
  void testSuccess_missingMaxDomainsAndCreatesInitializesToZero() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.BULK_PRICING)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
            .setAllowedTlds(ImmutableSet.of("foo"))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, 0))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setDiscountFraction(1.0)
            .build());
    runCommandForced("--price=USD 1000.00", "--next_billing_date=2012-03-17T00:00:00Z", "abc123");
    Optional<BulkPricingPackage> bulkPricingPackageOptional =
        tm().transact(() -> BulkPricingPackage.loadByTokenString("abc123"));
    assertThat(bulkPricingPackageOptional).isPresent();
    BulkPricingPackage bulkPricingPackage = bulkPricingPackageOptional.get();
    assertThat(bulkPricingPackage.getMaxDomains()).isEqualTo(0);
    assertThat(bulkPricingPackage.getMaxCreates()).isEqualTo(0);
    assertThat(bulkPricingPackage.getBulkPrice()).isEqualTo(Money.of(CurrencyUnit.USD, 1000));
    assertThat(bulkPricingPackage.getNextBillingDate())
        .isEqualTo(DateTime.parse("2012-03-17T00:00:00Z"));
    assertThat(bulkPricingPackage.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testSuccess_missingNextBillingDateInitializesToEndOfTime() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.BULK_PRICING)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
            .setAllowedTlds(ImmutableSet.of("foo"))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, 0))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setDiscountFraction(1.0)
            .build());
    runCommandForced("--max_domains=100", "--max_creates=500", "--price=USD 1000.00", "abc123");

    Optional<BulkPricingPackage> bulkPricingPackageOptional =
        tm().transact(() -> BulkPricingPackage.loadByTokenString("abc123"));
    assertThat(bulkPricingPackageOptional).isPresent();
    BulkPricingPackage bulkPricingPackage = bulkPricingPackageOptional.get();
    assertThat(bulkPricingPackage.getMaxDomains()).isEqualTo(100);
    assertThat(bulkPricingPackage.getMaxCreates()).isEqualTo(500);
    assertThat(bulkPricingPackage.getBulkPrice()).isEqualTo(Money.of(CurrencyUnit.USD, 1000));
    assertThat(bulkPricingPackage.getNextBillingDate()).isEqualTo(END_OF_TIME);
    assertThat(bulkPricingPackage.getLastNotificationSent()).isEmpty();
  }

  @Test
  void testFailure_missingPrice() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(TokenType.BULK_PRICING)
            .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
            .setAllowedTlds(ImmutableSet.of("foo"))
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, 0))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setDiscountFraction(1.0)
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
        .isEqualTo("BulkPrice is required when creating a new bulk pricing package");
  }
}
