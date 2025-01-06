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

package google.registry.util;

import com.google.common.base.Joiner;
import com.ibm.icu.text.IDNA;
import com.ibm.icu.text.IDNA.Info;

/**
 * A partial API-compatible replacement for {@link java.net.IDN} that replaces <a
 * href="http://www.ietf.org/rfc/rfc3490.txt">IDNA2003</a> processing with <a
 * href="http://unicode.org/reports/tr46/">UTS46 transitional processing</a>, with differences as
 * described in the <a href="http://www.unicode.org/reports/tr46/#IDNAComparison">UTS46
 * documentation</a>/
 *
 * <p>This class provides methods to convert internationalized domain names (IDNs) between Unicode
 * and ASCII-only <a href="http://www.ietf.org/rfc/rfc3492.txt">Punycode</a> form. It implements the
 * parts of the API from {@link java.net.IDN} that we care about, but will return different results
 * as defined by the differences between IDNA2003 and transitional UTS46.
 */
public final class Idn {

  /** Cached UTS46 with the flags we want. */
  private static final IDNA UTS46_INSTANCE = IDNA.getUTS46Instance(IDNA.CHECK_BIDI);

  /**
   * Translates a string from Unicode to ASCII Compatible Encoding (ACE), as defined by the ToASCII
   * operation of <a href="http://www.ietf.org/rfc/rfc3490.txt">RFC 3490</a>.
   *
   * <p>This method always uses <a href="http://unicode.org/reports/tr46/">UTS46 transitional
   * processing</a>.
   *
   * @param name a domain name, which may include multiple labels separated by dots
   * @throws IllegalArgumentException if the input string doesn't conform to RFC 3490 specification
   * @see java.net.IDN#toASCII(String)
   */
  public static String toASCII(String name) {
    Info info = new Info();
    StringBuilder result = new StringBuilder();
    UTS46_INSTANCE.nameToASCII(name, result, info);
    if (info.hasErrors()) {
      throw new IllegalArgumentException("Errors: " + Joiner.on(',').join(info.getErrors()));
    }
    return result.toString();
  }

  /**
   * Translates a string from ASCII Compatible Encoding (ACE) to Unicode, as defined by the
   * ToUnicode operation of <a href="http://www.ietf.org/rfc/rfc3490.txt">RFC 3490</a>.
   *
   * <p>This method always uses <a href="http://unicode.org/reports/tr46/">UTS46 transitional
   * processing</a>.
   *
   * <p>ToUnicode never fails. In case of any error, the input string is returned unmodified.
   *
   * @param name a domain name, which may include multiple labels separated by dots
   * @see java.net.IDN#toUnicode(String)
   */
  public static String toUnicode(String name) {
    Info info = new Info();
    StringBuilder result = new StringBuilder();
    UTS46_INSTANCE.nameToUnicode(name, result, info);
    return info.hasErrors() ? name : result.toString();
  }
}
