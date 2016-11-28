// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.tldconfig.idn.IdnLabelValidator.findValidIdnTableForTld;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.testing.InjectRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link IdnLabelValidator}. */
@RunWith(JUnit4.class)
public class IdnLabelValidatorTest {

  @Rule
  public final InjectRule inject = new InjectRule();

  private void doJapaneseLanguageTests(String tld) {
    assertThat(findValidIdnTableForTld("foo", tld)).isPresent();
    assertThat(findValidIdnTableForTld("12379foar", tld)).isPresent();
    assertThat(findValidIdnTableForTld("みんな", tld)).isPresent();
    assertThat(findValidIdnTableForTld("アシヨ", tld)).isPresent();
    assertThat(findValidIdnTableForTld("わみけ", tld)).isPresent();
    assertThat(findValidIdnTableForTld("みんなアシヨわみけabc", tld)).isPresent();
    assertThat(findValidIdnTableForTld("-みんなアシヨわみけ-", tld)).isPresent();
    assertThat(findValidIdnTableForTld("あいう〆", tld)).isPresent();
    assertThat(findValidIdnTableForTld("〆わをん", tld)).isPresent();

    // This should fail since it mixes Japanese characters with extended Latin characters. These are
    // allowed individually, but not together, since they are in separate IDN tables.
    assertThat(findValidIdnTableForTld("みんなアシヨわみけæ", tld)).isAbsent();

    // This fails because it has Cyrillic characters, which just aren't allowed in either IDN table.
    assertThat(findValidIdnTableForTld("aЖЗ", tld)).isAbsent();

    assertThat(findValidIdnTableForTld("abcdefghæ", tld)).isPresent();
    assertThat(findValidIdnTableForTld("happy", tld)).isPresent();
    assertThat(findValidIdnTableForTld("ite-love-you", tld)).isPresent();
    assertThat(findValidIdnTableForTld("凹凸商事", tld)).isPresent();
    assertThat(findValidIdnTableForTld("いすゞ製鉄", tld)).isPresent();
    assertThat(findValidIdnTableForTld("日々の生活", tld)).isPresent();
    assertThat(findValidIdnTableForTld("1000万円", tld)).isPresent();
    assertThat(findValidIdnTableForTld("たか--い", tld)).isPresent();
    assertThat(findValidIdnTableForTld("ザ・セール", tld)).isPresent();
    assertThat(findValidIdnTableForTld("example・例", tld)).isPresent();
    assertThat(findValidIdnTableForTld("カレー・ライス",   tld)).isPresent();
    assertThat(findValidIdnTableForTld("〆切・明日", tld)).isPresent();
    assertThat(findValidIdnTableForTld("そのスピードで",   tld)).isPresent();
    assertThat(findValidIdnTableForTld("visaクレジットカード", tld)).isPresent();
    assertThat(findValidIdnTableForTld("cdケース", tld)).isPresent();
    assertThat(findValidIdnTableForTld("らーめん",   tld)).isPresent();

    // These fail because they have a KATAKANA MIDDLE DOT or IDEOGRAPHIC_CLOSING_MARK without any
    // Japanese non-exception characters.
    assertThat(findValidIdnTableForTld("eco・driving", tld)).isAbsent();
    assertThat(findValidIdnTableForTld("・ー・", tld)).isAbsent();
    assertThat(findValidIdnTableForTld("〆〆example・・", tld)).isAbsent();
    assertThat(findValidIdnTableForTld("abc〆", tld)).isAbsent();
    assertThat(findValidIdnTableForTld("〆xyz", tld)).isAbsent();
    assertThat(findValidIdnTableForTld("〆bar・", tld)).isAbsent();

    // This is a Japanese label with exactly 15 characters.
    assertThat(findValidIdnTableForTld("カレー・ライスaaaaaaaa", tld)).isPresent();

    // Should fail since it has Japanese characters but is more than 15 characters long.
    assertThat(findValidIdnTableForTld("カレー・ライスaaaaaaaaa", tld)).isAbsent();

    // Should fail since it has a prolonged sound mark that is not preceded by Hiragana or Katakana
    // characters.
    assertThat(findValidIdnTableForTld("aー", tld)).isAbsent();
    assertThat(findValidIdnTableForTld("-ー", tld)).isAbsent();
    assertThat(findValidIdnTableForTld("0ー", tld)).isAbsent();
  }

  @Test
  public void testOverridenTables() {
    // Set .tld to have only the German table and not French.
    inject.setStaticField(
        IdnLabelValidator.class,
        "idnTableListsPerTld",
        ImmutableMap.of("tld", ImmutableList.of(IdnTableEnum.DE)));
    assertThat(findValidIdnTableForTld("foo", "tld")).isPresent();
    assertThat(findValidIdnTableForTld("ça",  "tld")).isAbsent();
  }
}
