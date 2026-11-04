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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.DomainNameUtils.canonicalizeAndValidateHostname;
import static google.registry.util.DomainNameUtils.canonicalizeHostname;
import static google.registry.util.DomainNameUtils.getSecondLevelDomain;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainNameUtils}. */
class DomainNameUtilsTest {

  @Test
  void testCanonicalizeHostname_succeeds() {
    assertThat(canonicalizeHostname("foo")).isEqualTo("foo");
    assertThat(canonicalizeHostname("FOO")).isEqualTo("foo");
    assertThat(canonicalizeHostname("foo.tld")).isEqualTo("foo.tld");
    assertThat(canonicalizeHostname("xn--q9jyb4c")).isEqualTo("xn--q9jyb4c");
    assertThat(canonicalizeHostname("XN--Q9JYB4C")).isEqualTo("xn--q9jyb4c");
    assertThat(canonicalizeHostname("みんな")).isEqualTo("xn--q9jyb4c");
    assertThat(canonicalizeHostname("みんな.みんな")).isEqualTo("xn--q9jyb4c.xn--q9jyb4c");
    assertThat(canonicalizeHostname("みんな.foo")).isEqualTo("xn--q9jyb4c.foo");
    assertThat(canonicalizeHostname("foo.みんな")).isEqualTo("foo.xn--q9jyb4c");
    assertThat(canonicalizeHostname("BAR.foo.みんな")).isEqualTo("bar.foo.xn--q9jyb4c");
    assertThat(canonicalizeHostname("ħ")).isEqualTo("xn--1ea");
  }

  @Test
  void testCanonicalizeHostname_allowsRdnsNames() {
    assertThat(canonicalizeHostname("119.63.227.45-ns1.jhz-tt.uk"))
        .isEqualTo("119.63.227.45-ns1.jhz-tt.uk");
  }

  @Test
  void testCanonicalizeHostname_retainsTrailingDot() {
    assertThat(canonicalizeHostname("みんな.みんな.")).isEqualTo("xn--q9jyb4c.xn--q9jyb4c.");
    assertThat(canonicalizeHostname("BAR.foo.みんな.")).isEqualTo("bar.foo.xn--q9jyb4c.");
    assertThat(canonicalizeHostname("cat.lol.")).isEqualTo("cat.lol.");
  }

  @Test
  void testCanonicalizeHostname_throwsOn34HyphenRule() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> canonicalizeHostname("119.63.227.45--ns1.jhz-tt.uk"));
    assertThat(thrown).hasMessageThat().contains("HYPHEN_3_4");
  }

  @Test
  void testCanonicalizeHostname_throwsOn34HyphenRule_withTrailingDot() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> canonicalizeHostname("119.63.227.45--ns1.jhz-tt.uk."));
    assertThat(thrown).hasMessageThat().contains("HYPHEN_3_4");
  }

  @Test
  void testCanonicalizeHostname_allows34HyphenOnTld() {
    assertThat(canonicalizeHostname("foobar.zz--main-2262")).isEqualTo("foobar.zz--main-2262");
    assertThat(canonicalizeHostname("foobar.zz--main-2262.")).isEqualTo("foobar.zz--main-2262.");
    assertThat(canonicalizeHostname("みんな.45--foo")).isEqualTo("xn--q9jyb4c.45--foo");
    assertThat(canonicalizeHostname("みんな.45--foo.")).isEqualTo("xn--q9jyb4c.45--foo.");
  }

  @Test
  void testCanonicalizeHostname_acePrefixUnicodeChars() {
    assertThrows(IllegalArgumentException.class, () -> canonicalizeHostname("xn--みんな"));
  }

  @Test
  void testCanonicalizeAndValidateHostname_succeeds() {
    assertThat(canonicalizeAndValidateHostname("b.example")).isEqualTo("b.example");
    assertThat(canonicalizeAndValidateHostname("EPP.Registrar.Example"))
        .isEqualTo("epp.registrar.example");
    assertThat(canonicalizeAndValidateHostname("COM.Google.Registrar."))
        .isEqualTo("com.google.registrar");
    assertThat(canonicalizeAndValidateHostname("epp.みんな")).isEqualTo("epp.xn--q9jyb4c");
    assertThat(canonicalizeAndValidateHostname("みんな.registrar.example"))
        .isEqualTo("xn--q9jyb4c.registrar.example");
    assertThat(canonicalizeAndValidateHostname("XN--Q9JYB4C.Registrar.Example"))
        .isEqualTo("xn--q9jyb4c.registrar.example");
    assertThat(canonicalizeAndValidateHostname("epp-01.c-d.example"))
        .isEqualTo("epp-01.c-d.example");
    String maxLabel = "a".repeat(63);
    assertThat(canonicalizeAndValidateHostname(maxLabel + ".example"))
        .isEqualTo(maxLabel + ".example");
    String maxHostname =
        "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
    assertThat(maxHostname).hasLength(253);
    assertThat(canonicalizeAndValidateHostname(maxHostname)).isEqualTo(maxHostname);
  }

  @Test
  void testCanonicalizeAndValidateHostname_invalid() {
    assertThrows(IllegalArgumentException.class, () -> canonicalizeAndValidateHostname(null));
    for (String invalid :
        ImmutableList.of(
            "",
            ".",
            "localhost",
            "localhost.",
            ".registrar.example",
            "epp..registrar.example",
            "epp.registrar.example..",
            "*.registrar.example",
            "epp_1.registrar.example",
            "-epp.registrar.example",
            "epp-.registrar.example",
            "ab--cd.registrar.example",
            "xn--みんな.example",
            "192.0.2.1",
            "2001:db8::1",
            "[2001:db8::1]",
            "https://epp.registrar.example",
            "epp.registrar.example:700",
            "epp.registrar.example/path",
            " epp.registrar.example",
            "epp.registrar.example ",
            "epp.registrar.ex ample",
            "a".repeat(64) + ".example",
            "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> canonicalizeAndValidateHostname(invalid),
          "Expected IllegalArgumentException for: " + invalid);
    }
  }

  @Test
  void testGetSecondLevelDomain_returnsProperDomain() {
    assertThat(getSecondLevelDomain("foo.bar", "bar")).isEqualTo("foo.bar");
    assertThat(getSecondLevelDomain("ns1.foo.bar", "bar")).isEqualTo("foo.bar");
    assertThat(getSecondLevelDomain("ns1.abc.foo.bar", "bar")).isEqualTo("foo.bar");
    assertThat(getSecondLevelDomain("ns1.abc.foo.bar", "foo.bar")).isEqualTo("abc.foo.bar");
  }

  @Test
  void testGetSecondLevelDomain_insufficientDomainNameDepth() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> getSecondLevelDomain("bar", "bar"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("hostName must be at least one level below the tld");
  }

  @Test
  void testGetSecondLevelDomain_domainNotUnderTld() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> getSecondLevelDomain("foo.bar", "abc"));
    assertThat(thrown).hasMessageThat().isEqualTo("hostName must be under the tld");
  }
}
