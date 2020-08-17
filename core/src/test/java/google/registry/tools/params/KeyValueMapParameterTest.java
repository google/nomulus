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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.tools.params.KeyValueMapParameter.CurrencyUnitToStringMap;
import google.registry.tools.params.KeyValueMapParameter.StringToIntegerMap;
import google.registry.tools.params.KeyValueMapParameter.StringToStringMap;
import org.joda.money.CurrencyUnit;
import org.joda.money.IllegalCurrencyException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link KeyValueMapParameter}. */
class KeyValueMapParameterTest {

  private final StringToStringMap stringToStringInstance = new StringToStringMap();
  private final StringToIntegerMap stringToIntegerInstance = new StringToIntegerMap();
  private final CurrencyUnitToStringMap currencyUnitToStringMap = new CurrencyUnitToStringMap();

  @Test
  void testSuccess_convertStringToString_singleEntry() {
    assertThat(stringToStringInstance.convert("key=foo"))
        .isEqualTo(ImmutableMap.of("key", "foo"));
  }

  @Test
  void testSuccess_convertStringToInteger_singleEntry() {
    assertThat(stringToIntegerInstance.convert("key=1"))
        .isEqualTo(ImmutableMap.of("key", 1));
  }

  @Test
  void testSuccess_convertCurrencyUnitToString_singleEntry() {
    assertThat(currencyUnitToStringMap.convert("USD=123abc"))
        .isEqualTo(ImmutableMap.of(CurrencyUnit.USD, "123abc"));
  }

  @Test
  void testSuccess_convertStringToString() {
    assertThat(stringToStringInstance.convert("key=foo,key2=bar"))
        .isEqualTo(ImmutableMap.of("key", "foo", "key2", "bar"));
  }

  @Test
  void testSuccess_convertStringToInteger() {
    assertThat(stringToIntegerInstance.convert("key=1,key2=2"))
        .isEqualTo(ImmutableMap.of("key", 1, "key2", 2));
  }

  @Test
  void testSuccess_convertCurrencyUnitToString() {
    assertThat(currencyUnitToStringMap.convert("USD=123abc,JPY=xyz789"))
        .isEqualTo(ImmutableMap.of(CurrencyUnit.USD, "123abc", CurrencyUnit.JPY, "xyz789"));
  }

  @Test
  void testSuccess_convertStringToString_empty() {
    assertThat(stringToStringInstance.convert("")).isEmpty();
  }

  @Test
  void testSuccess_convertStringToInteger_empty() {
    assertThat(stringToIntegerInstance.convert("")).isEmpty();
  }

  @Test
  void testSuccess_convertCurrencyUnitToString_empty() {
    assertThat(currencyUnitToStringMap.convert("")).isEmpty();
  }

  @Test
  void testFailure_convertStringToInteger_badType() {
    assertThrows(
        NumberFormatException.class, () -> stringToIntegerInstance.convert("key=1,key2=foo"));
  }

  @Test
  void testFailure_convertCurrencyUnitToString_badType() {
    IllegalCurrencyException thrown =
        assertThrows(
            IllegalCurrencyException.class,
            () -> currencyUnitToStringMap.convert("USD=123abc,XYZ=xyz789"));
    assertThat(thrown).hasMessageThat().contains("XYZ");
  }

  @Test
  void testFailure_convertStringToString_badSeparator() {
    assertThrows(
        IllegalArgumentException.class, () -> stringToStringInstance.convert("key=foo&key2=bar"));
  }

  @Test
  void testFailure_convertStringToInteger_badSeparator() {
    assertThrows(
        IllegalArgumentException.class, () -> stringToIntegerInstance.convert("key=1&key2=2"));
  }

  @Test
  void testFailure_convertCurrencyUnitToString_badSeparator() {
    assertThrows(
        IllegalArgumentException.class,
        () -> currencyUnitToStringMap.convert("USD=123abc&JPY=xyz789"));
  }

  @Test
  void testFailure_convertStringToString_badFormat() {
    assertThrows(IllegalArgumentException.class, () -> stringToStringInstance.convert("foo"));
  }

  @Test
  void testFailure_convertStringToInteger_badFormat() {
    assertThrows(IllegalArgumentException.class, () -> stringToIntegerInstance.convert("foo"));
  }

  @Test
  void testFailure_convertCurrencyUnitToString_badFormat() {
    assertThrows(IllegalArgumentException.class, () -> currencyUnitToStringMap.convert("foo"));
  }
}

