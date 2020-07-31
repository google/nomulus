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
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static google.registry.util.DomainNameUtils.getSecondLevelDomain;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainNameUtils}. */
class DomainNameUtilsTest {

  @Test
  void testCanonicalizeDomainName() {
    assertThat(canonicalizeDomainName("foo")).isEqualTo("foo");
    assertThat(canonicalizeDomainName("FOO")).isEqualTo("foo");
    assertThat(canonicalizeDomainName("foo.tld")).isEqualTo("foo.tld");
    assertThat(canonicalizeDomainName("xn--q9jyb4c")).isEqualTo("xn--q9jyb4c");
    assertThat(canonicalizeDomainName("XN--Q9JYB4C")).isEqualTo("xn--q9jyb4c");
    assertThat(canonicalizeDomainName("みんな")).isEqualTo("xn--q9jyb4c");
    assertThat(canonicalizeDomainName("みんな.みんな")).isEqualTo("xn--q9jyb4c.xn--q9jyb4c");
    assertThat(canonicalizeDomainName("みんな.foo")).isEqualTo("xn--q9jyb4c.foo");
    assertThat(canonicalizeDomainName("foo.みんな")).isEqualTo("foo.xn--q9jyb4c");
    assertThat(canonicalizeDomainName("ħ")).isEqualTo("xn--1ea");
  }

  @Test
  void testCanonicalizeDomainName_acePrefixUnicodeChars() {
    assertThrows(IllegalArgumentException.class, () -> canonicalizeDomainName("xn--みんな"));
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
