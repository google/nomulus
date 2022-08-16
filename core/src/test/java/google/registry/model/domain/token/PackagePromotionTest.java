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

package google.registry.model.domain.token;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.EntityTestCase;
import google.registry.model.domain.token.AllocationToken.TokenType;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableSet;

/** Unit tests for {@link PackagePromotion}. */
public class PackagePromotionTest extends EntityTestCase {

  public PackagePromotionTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    createTld("foo");
  }

  @Test
  void testPersistence() {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.PACKAGE)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setDiscountFraction(1)
                .build());

    PackagePromotion packagePromotion =
        persistResource(
            new PackagePromotion.Builder()
                .setToken(token)
                .setPackagePrice(Money.of(CurrencyUnit.USD, 10000))
                .setMaxCreates(40)
                .setMaxDomains(10)
                .setNextBillingDate(DateTime.parse("2011-11-12T05:00:00Z"))
                .build());

    assertThat(loadByEntity(packagePromotion)).isEqualTo(packagePromotion);
  }

  @Test
  void testFail_tokenIsNotPackage() {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.SINGLE_USE)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setDiscountFraction(1)
                .build());

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                persistResource(
                    new PackagePromotion.Builder()
                        .setToken(token)
                        .setPackagePrice(Money.of(CurrencyUnit.USD, 10000))
                        .setMaxCreates(40)
                        .setMaxDomains(10)
                        .setNextBillingDate(DateTime.parse("2011-11-12T05:00:00Z"))
                        .build()));

    assertThat(thrown).hasMessageThat().isEqualTo("Allocation token must be a PACKAGE type");
  }
}
