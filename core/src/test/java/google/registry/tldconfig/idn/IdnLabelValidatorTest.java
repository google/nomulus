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

package google.registry.tldconfig.idn;

import static com.google.common.truth.Truth8.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IdnLabelValidator}. */
class IdnLabelValidatorTest {

  private IdnLabelValidator idnLabelValidator = IdnLabelValidator.createDefaultIdnLabelValidator();

  private void doJapaneseLanguageTests(String tld) {
    assertThat(idnLabelValidator.findValidIdnTableForTld("foo", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("12379foar", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("みんな", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("アシヨ", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("わみけ", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("みんなアシヨわみけabc", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("-みんなアシヨわみけ-", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("あいう〆", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("〆わをん", tld)).isPresent();

    // This should fail since it mixes Japanese characters with extended Latin characters. These are
    // allowed individually, but not together, since they are in separate IDN tables.
    assertThat(idnLabelValidator.findValidIdnTableForTld("みんなアシヨわみけæ", tld)).isEmpty();

    // This fails because it has Cyrillic characters, which just aren't allowed in either IDN table.
    assertThat(idnLabelValidator.findValidIdnTableForTld("aЖЗ", tld)).isEmpty();

    assertThat(idnLabelValidator.findValidIdnTableForTld("abcdefghæ", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("happy", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("ite-love-you", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("凹凸商事", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("いすゞ製鉄", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("日々の生活", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("1000万円", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("たか--い", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("ザ・セール", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("example・例", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("カレー・ライス", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("〆切・明日", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("そのスピードで", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("visaクレジットカード", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("cdケース", tld)).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("らーめん", tld)).isPresent();

    // These fail because they have a KATAKANA MIDDLE DOT or IDEOGRAPHIC_CLOSING_MARK without any
    // Japanese non-exception characters.
    assertThat(idnLabelValidator.findValidIdnTableForTld("eco・driving", tld)).isEmpty();
    assertThat(idnLabelValidator.findValidIdnTableForTld("・ー・", tld)).isEmpty();
    assertThat(idnLabelValidator.findValidIdnTableForTld("〆〆example・・", tld)).isEmpty();
    assertThat(idnLabelValidator.findValidIdnTableForTld("abc〆", tld)).isEmpty();
    assertThat(idnLabelValidator.findValidIdnTableForTld("〆xyz", tld)).isEmpty();
    assertThat(idnLabelValidator.findValidIdnTableForTld("〆bar・", tld)).isEmpty();

    // This is a Japanese label with exactly 15 characters.
    assertThat(idnLabelValidator.findValidIdnTableForTld("カレー・ライスaaaaaaaa", tld)).isPresent();

    // Should fail since it has Japanese characters but is more than 15 characters long.
    assertThat(idnLabelValidator.findValidIdnTableForTld("カレー・ライスaaaaaaaaa", tld)).isEmpty();

    // Should fail since it has a prolonged sound mark that is not preceded by Hiragana or Katakana
    // characters.
    assertThat(idnLabelValidator.findValidIdnTableForTld("aー", tld)).isEmpty();
    assertThat(idnLabelValidator.findValidIdnTableForTld("-ー", tld)).isEmpty();
    assertThat(idnLabelValidator.findValidIdnTableForTld("0ー", tld)).isEmpty();
  }

  @Test
  void testMinna() {
    doJapaneseLanguageTests("xn--q9jyb4c");
  }

  @Test
  void testFoo() {
    doJapaneseLanguageTests("foo");
  }

  @Test
  void testSoy() {
    doJapaneseLanguageTests("soy");
  }

  @Test
  void testOverridenTables() {
    // Set .tld to have only the extended latin table and not japanese.
    idnLabelValidator =
        new IdnLabelValidator(
            ImmutableMap.of("tld", ImmutableList.of(IdnTableEnum.EXTENDED_LATIN)));
    assertThat(idnLabelValidator.findValidIdnTableForTld("foo", "tld")).isPresent();
    assertThat(idnLabelValidator.findValidIdnTableForTld("みんな", "tld")).isEmpty();
  }
}
