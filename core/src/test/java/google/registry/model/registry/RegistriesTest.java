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

package google.registry.model.registry;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.newRegistry;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;

import com.google.common.net.InternetDomainName;
import google.registry.model.registry.Registry.TldType;
import google.registry.testing.AppEngineRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Registries}. */
@RunWith(JUnit4.class)
public class RegistriesTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  private void initTestTlds() {
    createTlds("foo", "a.b.c"); // Test a multipart tld.
  }

  @Test
  public void testGetTlds() {
    initTestTlds();
    assertThat(Registries.getTlds()).containsExactly("foo", "a.b.c");
  }

  @Test
  public void test_getTldEntities() {
    initTestTlds();
    persistResource(newRegistry("testtld", "TESTTLD").asBuilder().setTldType(TldType.TEST).build());
    assertThat(Registries.getTldEntitiesOfType(TldType.REAL))
        .containsExactly(Registry.get("foo"), Registry.get("a.b.c"));
    assertThat(Registries.getTldEntitiesOfType(TldType.TEST))
        .containsExactly(Registry.get("testtld"));
  }

  @Test
  public void testGetTlds_withNoRegistriesPersisted_returnsEmptySet() {
    assertThat(Registries.getTlds()).isEmpty();
  }

  @Test
  public void testAssertTldExists_doesExist() {
    initTestTlds();
    Registries.assertTldExists("foo");
    Registries.assertTldExists("a.b.c");
  }

  @Test
  public void testAssertTldExists_doesntExist() {
    initTestTlds();
    assertThrows(IllegalArgumentException.class, () -> Registries.assertTldExists("baz"));
  }

  @Test
  public void testFindTldForName() {
    initTestTlds();
    assertThat(Registries.findTldForName(InternetDomainName.from("example.foo")).get().toString())
        .isEqualTo("foo");
    assertThat(Registries.findTldForName(InternetDomainName.from("x.y.a.b.c")).get().toString())
        .isEqualTo("a.b.c");
    // We don't have an "example" tld.
    assertThat(Registries.findTldForName(InternetDomainName.from("foo.example"))).isEmpty();
    // A tld is not a match for itself.
    assertThat(Registries.findTldForName(InternetDomainName.from("foo"))).isEmpty();
    // The name must match the entire tld.
    assertThat(Registries.findTldForName(InternetDomainName.from("x.y.a.b"))).isEmpty();
    assertThat(Registries.findTldForName(InternetDomainName.from("x.y.b.c"))).isEmpty();
    // Substring tld matches aren't considered.
    assertThat(Registries.findTldForName(InternetDomainName.from("example.barfoo"))).isEmpty();
  }
}
