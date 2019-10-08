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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.BloomFilter;
import java.math.BigDecimal;
import org.joda.money.CurrencyUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PremiumList}. */
@RunWith(JUnit4.class)
public class PremiumListTest {

  private static final ImmutableMap<String, BigDecimal> TEST_PRICES =
      ImmutableMap.of(
          "silver",
          BigDecimal.valueOf(10.23),
          "gold",
          BigDecimal.valueOf(1305.47),
          "palladium",
          BigDecimal.valueOf(1552.78));

  @Test
  public void bloomFilter_worksCorrectly() {
    BloomFilter<String> bloomFilter =
        PremiumList.create("testname", CurrencyUnit.USD, TEST_PRICES).getBloomFilter();
    ImmutableSet.of("silver", "gold", "palladium")
        .forEach(l -> assertThat(bloomFilter.mightContain(l)).isTrue());
    ImmutableSet.of("dirt", "pyrite", "zirconia")
        .forEach(l -> assertThat(bloomFilter.mightContain(l)).isFalse());
  }
}
