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

package google.registry.model.tld;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.tld.Tlds.hasActiveBsaEnrollment;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.newTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.net.InternetDomainName;
import google.registry.model.tld.Tld.TldType;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link Tlds}. */
class TldsTest {

  Clock fakeClock = new FakeClock();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  private void initTestTlds() {
    createTlds("foo", "a.b.c"); // Test a multipart tld.
  }

  @Test
  void testGetTlds() {
    initTestTlds();
    assertThat(Tlds.getTlds()).containsExactly("foo", "a.b.c");
  }

  @Test
  void test_getTldEntities() {
    initTestTlds();
    persistResource(newTld("testtld", "TESTTLD").asBuilder().setTldType(TldType.TEST).build());
    assertThat(Tlds.getTldEntitiesOfType(TldType.REAL))
        .containsExactly(Tld.get("foo"), Tld.get("a.b.c"));
    assertThat(Tlds.getTldEntitiesOfType(TldType.TEST)).containsExactly(Tld.get("testtld"));
  }

  @Test
  void testGetTlds_withNoRegistriesPersisted_returnsEmptySet() {
    assertThat(Tlds.getTlds()).isEmpty();
  }

  @Test
  void testAssertTldExists_doesExist() {
    initTestTlds();
    Tlds.assertTldExists("foo");
    Tlds.assertTldExists("a.b.c");
  }

  @Test
  void testAssertTldExists_doesntExist() {
    initTestTlds();
    assertThrows(IllegalArgumentException.class, () -> Tlds.assertTldExists("baz"));
  }

  @Test
  void testFindTldForName() {
    initTestTlds();
    assertThat(Tlds.findTldForName(InternetDomainName.from("example.foo")).get().toString())
        .isEqualTo("foo");
    assertThat(Tlds.findTldForName(InternetDomainName.from("x.y.a.b.c")).get().toString())
        .isEqualTo("a.b.c");
    // We don't have an "example" tld.
    assertThat(Tlds.findTldForName(InternetDomainName.from("foo.example"))).isEmpty();
    // A tld is not a match for itself.
    assertThat(Tlds.findTldForName(InternetDomainName.from("foo"))).isEmpty();
    // The name must match the entire tld.
    assertThat(Tlds.findTldForName(InternetDomainName.from("x.y.a.b"))).isEmpty();
    assertThat(Tlds.findTldForName(InternetDomainName.from("x.y.b.c"))).isEmpty();
    // Substring tld matches aren't considered.
    assertThat(Tlds.findTldForName(InternetDomainName.from("example.barfoo"))).isEmpty();
  }

  @Test
  void testHasActiveBsaEnrollment_noneEnrolled() {
    initTestTlds();
    assertThat(hasActiveBsaEnrollment(fakeClock.nowUtc())).isFalse();
  }

  @Test
  void testHasActiveBsaEnrollment_enrolledInTheFuture() {
    initTestTlds();
    persistResource(
        Tld.get("foo")
            .asBuilder()
            .setBsaEnrollStartTime(Optional.of(fakeClock.nowUtc().plusSeconds(1)))
            .build());
    assertThat(hasActiveBsaEnrollment(fakeClock.nowUtc())).isFalse();
  }

  @Test
  void testHasActiveBsaEnrollment_enrolledIsTestTld() {
    initTestTlds();
    persistResource(
        Tld.get("foo")
            .asBuilder()
            .setTldType(TldType.TEST)
            .setBsaEnrollStartTime(Optional.of(fakeClock.nowUtc().minusMillis(1)))
            .build());
    assertThat(hasActiveBsaEnrollment(fakeClock.nowUtc())).isFalse();
  }

  @Test
  void testHasActiveBsaEnrollment_enrolled() {
    initTestTlds();
    persistResource(
        Tld.get("foo")
            .asBuilder()
            .setBsaEnrollStartTime(Optional.of(fakeClock.nowUtc().minusMillis(1)))
            .build());
    assertThat(hasActiveBsaEnrollment(fakeClock.nowUtc())).isTrue();
  }
}
