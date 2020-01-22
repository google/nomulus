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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import google.registry.request.HttpException.UnprocessableEntityException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdapSearchPattern}. */
@RunWith(JUnit4.class)
public class RdapSearchPatternTest {

  @Test
  public void testNoWildcards_ok() {
    RdapSearchPattern rdapSearchPattern = RdapSearchPattern.createFromLdhDomainName("example.lol");
    assertThat(rdapSearchPattern.getInitialString()).isEqualTo("example.lol");
    assertThat(rdapSearchPattern.getHasWildcard()).isFalse();
    assertThat(rdapSearchPattern.getSuffix()).isNull();
  }

  @Test
  public void testWildcardNoTld_ok() {
    RdapSearchPattern rdapSearchPattern = RdapSearchPattern.createFromLdhDomainName("exam*");
    assertThat(rdapSearchPattern.getInitialString()).isEqualTo("exam");
    assertThat(rdapSearchPattern.getHasWildcard()).isTrue();
    assertThat(rdapSearchPattern.getSuffix()).isNull();
  }

  @Test
  public void testWildcardTld_ok() {
    RdapSearchPattern rdapSearchPattern = RdapSearchPattern.createFromLdhDomainName("exam*.lol");
    assertThat(rdapSearchPattern.getInitialString()).isEqualTo("exam");
    assertThat(rdapSearchPattern.getHasWildcard()).isTrue();
    assertThat(rdapSearchPattern.getSuffix()).isEqualTo("lol");
  }

  @Test
  public void testWildcardAtStart_ok() {
    RdapSearchPattern rdapSearchPattern = RdapSearchPattern.createFromLdhDomainName("*.lol");
    assertThat(rdapSearchPattern.getInitialString()).isEmpty();
    assertThat(rdapSearchPattern.getHasWildcard()).isTrue();
    assertThat(rdapSearchPattern.getSuffix()).isEqualTo("lol");
  }

  @Test
  public void testWildcardOnly_ok() {
    RdapSearchPattern rdapSearchPattern = RdapSearchPattern.createFromLdhDomainName("*");
    assertThat(rdapSearchPattern.getInitialString()).isEmpty();
    assertThat(rdapSearchPattern.getHasWildcard()).isTrue();
    assertThat(rdapSearchPattern.getSuffix()).isNull();
  }

  @Test
  public void testMultipleWildcards_unprocessable() {
    assertThrows(
        UnprocessableEntityException.class,
        () -> RdapSearchPattern.createFromLdhDomainName("ex*am*.lol"));
  }

  @Test
  public void testWildcardNotAtEnd_unprocessable() {
    assertThrows(
        UnprocessableEntityException.class,
        () -> RdapSearchPattern.createFromLdhDomainName("ex*am"));
  }

  @Test
  public void testWildcardNotAtEndWithTld_unprocessable() {
    assertThrows(
        UnprocessableEntityException.class,
        () -> RdapSearchPattern.createFromLdhDomainName("ex*am.lol"));
  }

  @Test
  public void testShortString_ok() {
    RdapSearchPattern rdapSearchPattern = RdapSearchPattern.createFromLdhDomainName("e");
    assertThat(rdapSearchPattern.getInitialString()).isEqualTo("e");
    assertThat(rdapSearchPattern.getHasWildcard()).isFalse();
    assertThat(rdapSearchPattern.getSuffix()).isNull();
  }

  @Test
  public void testZeroLengthSuffix_unprocessable() {
    assertThrows(
        UnprocessableEntityException.class,
        () -> RdapSearchPattern.createFromLdhDomainName("exam*."));
  }

  @Test
  public void testNextInitialString_alpha() {
    RdapSearchPattern rdapSearchPattern = RdapSearchPattern.createFromLdhDomainName("exam*.lol");
    assertThat(rdapSearchPattern.getNextInitialString()).isEqualTo("exan");
  }

  @Test
  public void testNextInitialString_unicode_translatedToPunycode() {
    RdapSearchPattern rdapSearchPattern =
        RdapSearchPattern.createFromLdhOrUnicodeDomainName("cat.みんな");
    assertThat(rdapSearchPattern.getNextInitialString()).isEqualTo("cat.xn--q9jyb4d");
  }

  @Test
  public void testUnicodeString_noWildcard() {
    RdapSearchPattern rdapSearchPattern =
        RdapSearchPattern.createFromUnicodeString("unicode みんに string");
    assertThat(rdapSearchPattern.getInitialString()).isEqualTo("unicode みんに string");
    assertThat(rdapSearchPattern.getHasWildcard()).isFalse();
    assertThat(rdapSearchPattern.getSuffix()).isNull();
  }

  @Test
  public void testUnicodeString_withWildcard() {
    RdapSearchPattern rdapSearchPattern =
        RdapSearchPattern.createFromUnicodeString("unicode みんに string*");
    assertThat(rdapSearchPattern.getInitialString()).isEqualTo("unicode みんに string");
    assertThat(rdapSearchPattern.getHasWildcard()).isTrue();
    assertThat(rdapSearchPattern.getSuffix()).isNull();
  }

  @Test
  public void testUnicodeString_middleWildcard() {
    assertThrows(
        UnprocessableEntityException.class,
        () -> RdapSearchPattern.createFromLdhDomainName("unicode みんに *string"));
  }
}
