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

package google.registry.tldconfig.idn;

import static java.lang.Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
import static java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS;
import static java.lang.Character.UnicodeBlock.HIRAGANA;
import static java.lang.Character.UnicodeBlock.KATAKANA;

import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.errorprone.annotations.Immutable;
import java.lang.Character.UnicodeBlock;
import java.util.Objects;

/**
 * Validates Japanese language domain labels. This class should only be used with a Japanese
 * language IDN table.
 */
@Immutable
class JapaneseLanguageValidator extends LanguageValidator {

  /** Any string with Japanese characters can have at most 15 characters. */
  private static final int MAX_LENGTH_JAPANESE_STRING = 15;

  /** Equals the codepoint for the character '〆'. */
  private static final int IDEOGRAPHIC_CLOSING_MARK = 0x3006;

  /** Equals the codepoint for the character '・'. */
  private static final int KATAKANA_MIDDLE_DOT = 0x30FB;

  /** Equals the codepoint for the character 'ー'. */
  private static final int KATAKANA_HIRAGANA_PROLONGED_SOUND_MARK = 0x30FC;

  /** The set of {@link UnicodeBlock} objects containing valid Japanese codepoints. */
  private static final ImmutableSet<UnicodeBlock> JAPANESE_UNICODE_BLOCKS = ImmutableSet.of(
      CJK_SYMBOLS_AND_PUNCTUATION, HIRAGANA, KATAKANA, CJK_UNIFIED_IDEOGRAPHS);

  /**
   * Codepoints which are technically considered to be in the Japanese language, but are
   * "exceptions" in that they can not appear in a label with a KATAKANA MIDDLE DOT or
   * IDEOGRAPHIC_CLOSING_MARK unless other Japanese non-exception codepoints are also present.
   */
  private static final ImmutableRangeSet<Integer> JAPANESE_EXCEPTION_CODEPOINTS =
      new ImmutableRangeSet.Builder<Integer>()
      .add(Range.singleton(IDEOGRAPHIC_CLOSING_MARK))
      .add(Range.singleton(KATAKANA_MIDDLE_DOT))
      .add(Range.singleton(KATAKANA_HIRAGANA_PROLONGED_SOUND_MARK))
      .build();

  @Override
  boolean isValidLabelForLanguage(String label) {
    boolean requiresJapaneseNonExceptionCodepoint = false;
    boolean hasJapaneseCodepoint = false;
    boolean hasJapaneseNonExceptionCodepoint = false;

    final int length = label.length();
    int codepoints = 0;
    UnicodeBlock precedingUnicodeBlock = null;
    for (int i = 0; i < length; ) {
      int codepoint = label.codePointAt(i);
      UnicodeBlock unicodeBlock = UnicodeBlock.of(codepoint);
      boolean isException = JAPANESE_EXCEPTION_CODEPOINTS.contains(codepoint);
      boolean isJapanese = JAPANESE_UNICODE_BLOCKS.contains(unicodeBlock);

      // A label containing KATAKANA_MIDDLE_DOT or IDEOGRAPHIC_CLOSING_MARK requires a Japanese
      // language codepoint to also appear in the label.
      if (codepoint == KATAKANA_MIDDLE_DOT || codepoint == IDEOGRAPHIC_CLOSING_MARK) {
        requiresJapaneseNonExceptionCodepoint = true;
      }

      // The KATAKANA_HIRAGANA_PROLONGED_SOUND_MARK can only occur after a HIRAGANA or KATAKANA
      // character.
      if (codepoint == KATAKANA_HIRAGANA_PROLONGED_SOUND_MARK
          && !Objects.equals(precedingUnicodeBlock, HIRAGANA)
          && !Objects.equals(precedingUnicodeBlock, KATAKANA)) {
        return false;
      }

      // If a codepoint is Japanese but not an "exception" codepoint, then it must a non-exception
      // Japanese codepoint.
      if (isJapanese && !isException) {
        hasJapaneseNonExceptionCodepoint = true;
      }

      // Make a note if we've seen any Japanese codepoint. Note that this object should really only
      // be used on a Japanese IDN table, and thus any non-ASCII codepoint should really be
      // Japanese. But we do the additional check again the characters UnicodeBlock just in case.
      if (isJapanese) {
        hasJapaneseCodepoint = true;
      }

      // Some codepoints take up more than one character in Java strings (e.g. high and low
      // surrogates).
      i += Character.charCount(codepoint);
      ++codepoints;
      precedingUnicodeBlock = unicodeBlock;
    }

    // A label with the KATAKANA MIDDLE DOT or IDEOGRAPHIC_CLOSING_MARK codepoint must also have
    // some Japanese character in the label. The Japanese "exception" characters do not count in
    // this regard.
    if (requiresJapaneseNonExceptionCodepoint && !hasJapaneseNonExceptionCodepoint) {
      return false;
    }

    // Any label with Japanese characters (including "exception" characters) can only be 15
    // codepoints long.
    return !(hasJapaneseCodepoint && (codepoints > MAX_LENGTH_JAPANESE_STRING));

  }
}
