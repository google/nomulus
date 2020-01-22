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

package google.registry.tools.params;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import org.joda.money.Money;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link MoneyParameter}. */
@RunWith(JUnit4.class)
public class MoneyParameterTest {
  private final MoneyParameter instance = new MoneyParameter();

  @Test
  public void testConvert_withCurrency() {
    assertThat(instance.convert("USD 777.99")).isEqualTo(Money.parse("USD 777.99"));
  }

  @Test
  public void testConvert_negative() {
    assertThat(instance.convert("USD -777.99")).isEqualTo(Money.parse("USD -777.99"));
  }

  @Test
  public void testConvert_missingSpace_isForgiving() {
    assertThat(instance.convert("USD777.99")).isEqualTo(Money.parse("USD 777.99"));
  }

  @Test
  public void testConvert_lowercase_isForgiving() {
    assertThat(instance.convert("usd777.99")).isEqualTo(Money.parse("USD 777.99"));
  }

  @Test
  public void testConvert_badCurrency_throws() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("FOO 1337"));
  }

  @Test
  public void testConvert_null_throws() {
    assertThrows(NullPointerException.class, () -> instance.convert(null));
  }

  @Test
  public void testConvert_empty_throws() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert(""));
  }

  @Test
  public void testConvert_sillyString_throws() {
    assertThrows(IllegalArgumentException.class, () -> instance.convert("foo"));
  }

  @Test
  public void testValidate_sillyString_throws() {
    ParameterException thrown =
        assertThrows(ParameterException.class, () -> instance.validate("--money", "foo"));
    assertThat(thrown).hasMessageThat().contains("--money=foo not valid");
  }

  @Test
  public void testValidate_correctInput() {
    instance.validate("--money", "USD 777");
  }
}
