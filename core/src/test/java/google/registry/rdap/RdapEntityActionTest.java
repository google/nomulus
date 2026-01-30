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
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomain;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHost;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarPocs;
import static google.registry.testing.GsonSubject.assertAboutJson;
import static org.mockito.Mockito.verify;

import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapEntityAction}. */
class RdapEntityActionTest extends RdapActionBaseTestCase<RdapEntityAction> {

  RdapEntityActionTest() {
    super(RdapEntityAction.class);
  }

  private Registrar registrarLol;

  @BeforeEach
  void beforeEach() {
    // lol
    createTld("lol");
    registrarLol = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE, 101L));
    persistResources(makeRegistrarPocs(registrarLol));
    Host host1 = persistResource(makeHost("ns1.cat.lol", "1.2.3.4"));
    Host host2 = persistResource(makeHost("ns2.cat.lol", "bad:f00d:cafe:0:0:0:15:beef"));
    persistResource(makeDomain("cat.lol", host1, host2, registrarLol));
    // xn--q9jyb4c
    createTld("xn--q9jyb4c");
    Registrar registrarIdn = persistResource(
        makeRegistrar("idnregistrar", "IDN Registrar", Registrar.State.ACTIVE, 102L));
    persistResources(makeRegistrarPocs(registrarIdn));
    // 1.tld
    createTld("1.tld");
    Registrar registrar1tld = persistResource(
        makeRegistrar("1tldregistrar", "Multilevel Registrar", Registrar.State.ACTIVE, 103L));
    persistResources(makeRegistrarPocs(registrar1tld));
    // deleted registrar
    Registrar registrarDeleted = persistResource(
        makeRegistrar("deletedregistrar", "Yes Virginia <script>", Registrar.State.PENDING, 104L));
    persistResources(makeRegistrarPocs(registrarDeleted));
  }

  @Test
  void testUnknownEntity_RoidPattern_notFound() {
    assertAboutJson()
        .that(generateActualJson("_MISSING-ENTITY_"))
        .isEqualTo(generateExpectedJsonError("_MISSING-ENTITY_ not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testUnknownEntity_IanaPattern_notFound() {
    assertAboutJson()
        .that(generateActualJson("123"))
        .isEqualTo(generateExpectedJsonError("123 not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testUnknownEntity_notRoidNotIana_notFound() {
    // Since we allow search by registrar name, every string is a possible name
    assertAboutJson()
        .that(generateActualJson("some,random,string"))
        .isEqualTo(generateExpectedJsonError("some,random,string not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testRegistrar_found() {
    assertAboutJson()
        .that(generateActualJson("101"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addFullRegistrar("101", "Yes Virginia <script>", "active", null)
                    .load("rdap_registrar.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testRegistrarByName_found() {
    assertAboutJson()
        .that(generateActualJson("IDN%20Registrar"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addFullRegistrar("102", "IDN Registrar", "active", null)
                    .load("rdap_registrar.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testRegistrar102_works() {
    assertAboutJson()
        .that(generateActualJson("102"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addFullRegistrar("102", "IDN Registrar", "active", null)
                    .load("rdap_registrar.json")));
  }

  @Test
  void testRegistrar103_works() {
    assertAboutJson()
        .that(generateActualJson("103"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addFullRegistrar("103", "Multilevel Registrar", "active", null)
                    .load("rdap_registrar.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testRegistrar104_notFound() {
    assertAboutJson()
        .that(generateActualJson("104"))
        .isEqualTo(generateExpectedJsonError("104 not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testRegistrar104_notFound_deletedFlagWhenNotLoggedIn() {
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("104"))
        .isEqualTo(generateExpectedJsonError("104 not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testRegistrar104_found_deletedFlagWhenLoggedIn() {
    login("deletedregistrar");
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("104"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addFullRegistrar("104", "Yes Virginia <script>", "inactive", null)
                    .load("rdap_registrar.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testRegistrar104_notFound_deletedFlagWhenLoggedInAsOther() {
    login("1tldregistrar");
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("104"))
        .isEqualTo(generateExpectedJsonError("104 not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testRegistrar104_found_deletedFlagWhenLoggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("104"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addFullRegistrar("104", "Yes Virginia <script>", "inactive", null)
                    .load("rdap_registrar.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testRegistrar105_doesNotExist() {
    assertAboutJson()
        .that(generateActualJson("105"))
        .isEqualTo(generateExpectedJsonError("105 not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testQueryParameter_ignored() {
    login("evilregistrar");
    assertAboutJson()
        .that(generateActualJson("101?key=value"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addFullRegistrar("101", "Yes Virginia <script>", "active", null)
                    .load("rdap_registrar.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testMetrics() {
    generateActualJson("101");
    verify(rdapMetrics)
        .updateMetrics(
            RdapMetrics.RdapMetricInformation.builder()
                .setEndpointType(EndpointType.ENTITY)
                .setSearchType(SearchType.NONE)
                .setWildcardType(WildcardType.INVALID)
                .setPrefixLength(0)
                .setIncludeDeleted(false)
                .setRegistrarSpecified(false)
                .setRole(RdapAuthorization.Role.PUBLIC)
                .setRequestMethod(Action.Method.GET)
                .setStatusCode(200)
                .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE)
                .build());
  }
}
