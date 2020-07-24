// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.tld;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.schema.tld.PremiumListUtils.parseToPremiumList;
import static org.junit.Assert.assertThrows;

import google.registry.model.registry.label.PremiumList;
import google.registry.testing.AppEngineExtension;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PremiumListUtils}. */
class PremiumListUtilsTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @Test
  void parseInputToPremiumList_works() {
    PremiumList premiumList =
        parseToPremiumList("testlist", "foo,USD 99.50\n" + "bar,USD 30\n" + "baz,USD 10\n");
    assertThat(premiumList.getName()).isEqualTo("testlist");
    assertThat(premiumList.getLabelsToPrices())
        .containsExactly("foo", twoDigits(99.50), "bar", twoDigits(30), "baz", twoDigits(10));
  }

  @Test
  void parseInputToPremiumList_throwsOnInconsistentCurrencies() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parseToPremiumList(
                    "testlist", "foo,USD 99.50\n" + "bar,USD 30\n" + "baz,JPY 990\n"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("The Cloud SQL schema requires exactly one currency, but got: [JPY, USD]");
  }

  private static BigDecimal twoDigits(double num) {
    return BigDecimal.valueOf((long) (num * 100.0), 2);
  }
}
