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

import static com.google.common.io.Resources.readLines;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URL;

/** Wrapper enum that loads all {@link IdnTable} resources into memory. */
public enum IdnTableEnum {

  /**
   * Extended Latin, as used on our existing TLD launches prior to 2023.
   *
   * <p>As of 2023 this table is no longer conformant with ICANN's IDN policies for new launches, so
   * it is retained solely for legacy compatibility with already-launched TLDs.
   */
  EXTENDED_LATIN("extended_latin.txt"),

  /**
   * Extended Latin, but with confusable characters removed.
   *
   * <p>This is compatible with ICANN's requirements as of 2023, and is used for the Dads and Grads
   * TLDs and all subsequent TLD launches. Note that confusable characters consist of various
   * letters with diacritic marks on them, e.g. U+00EF (LATIN SMALL LETTER I WITH DIAERESIS) is not
   * allowed because it is confusable with the standard i.
   */
  UNCONFUSABLE_LATIN("unconfusable_latin.txt"),

  /**
   * ICANN LGR 2025 Latin, but with confusable characters removed.
   *
   * <p>This is based on <a
   * href="https://www.icann.org/sites/default/files/packages/lgr/lgr-second-level-latin-full-variant-script-24jan24-en.html">ICANN's
   * LGR table</a>, but is simpler.
   */
  AUGMENTED_LATIN("augmented_latin.txt"),

  /**
   * Japanese, as used on our existing TLD launches prior to 2023.
   *
   * <p>As of 2023 this table is no longer conformant with ICANN's IDN policies for new launches, so
   * it is retained solely for legacy compatibility with already-launched TLDs.
   */
  JA("japanese.txt");

  private final IdnTable table;

  IdnTableEnum(String filename) {
    this.table = load(Ascii.toLowerCase(name()), filename);
  }

  public IdnTable getTable() {
    return table;
  }

  private static IdnTable load(String tableName, String filename) {
    try {
      URL resource = Resources.getResource(IdnTableEnum.class, filename);
      return IdnTable.createFrom(
          tableName, readLines(resource, UTF_8), LanguageValidator.get(tableName));
    } catch (IOException e) {
      throw new RuntimeException(e);  // should never happen
    }
  }
}
