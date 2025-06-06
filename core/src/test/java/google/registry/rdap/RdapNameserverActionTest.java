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
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.GsonSubject.assertAboutJson;
import static org.mockito.Mockito.verify;

import google.registry.model.registrar.Registrar;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.testing.FullFieldsTestEntityHelper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapNameserverAction}. */
class RdapNameserverActionTest extends RdapActionBaseTestCase<RdapNameserverAction> {

  RdapNameserverActionTest() {
    super(RdapNameserverAction.class);
  }

  @BeforeEach
  void beforeEach() {
    // normal
    createTld("lol");
    FullFieldsTestEntityHelper.makeAndPersistHost(
        "ns1.cat.lol", "1.2.3.4", clock.nowUtc().minusYears(1));
    // idn
    createTld("xn--q9jyb4c");
    FullFieldsTestEntityHelper.makeAndPersistHost(
        "ns1.cat.xn--q9jyb4c", "bad:f00d:cafe:0:0:0:15:beef", clock.nowUtc().minusYears(1));
    // multilevel
    createTld("1.tld");
    FullFieldsTestEntityHelper.makeAndPersistHost(
        "ns1.domain.1.tld", "5.6.7.8", clock.nowUtc().minusYears(1));
    // deleted
    persistResource(
        FullFieldsTestEntityHelper.makeAndPersistHost(
                "nsdeleted.cat.lol", "1.2.3.4", clock.nowUtc().minusYears(1))
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusMonths(1))
            .build());
    // other registrar
    persistResource(
        makeRegistrar("otherregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE, 102L));
    // external
    FullFieldsTestEntityHelper.makeAndPersistHost(
        "ns1.domain.external", "9.10.11.12", clock.nowUtc().minusYears(1));
  }

  @Test
  void testInvalidNameserver_returns400() {
    assertAboutJson()
        .that(generateActualJson("invalid/host/name"))
        .isEqualTo(
            generateExpectedJsonError(
                "invalid/host/name is not a valid nameserver: Invalid host name", 400));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testUnknownNameserver_returns404() {
    assertAboutJson()
        .that(generateActualJson("ns1.missing.com"))
        .isEqualTo(generateExpectedJsonError("ns1.missing.com not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testValidNameserver_works() {
    assertAboutJson()
        .that(generateActualJson("ns1.cat.lol"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4", "STATUS", "active")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testTrailingDot_getsIgnored() {
    assertAboutJson()
        .that(generateActualJson("ns1.cat.lol."))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4", "STATUS", "active")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testUpperCase_getsCanonicalized() {
    assertAboutJson()
        .that(generateActualJson("Ns1.CaT.lOl."))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4", "STATUS", "active")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testQueryParameter_getsIgnored() {
    assertAboutJson()
        .that(generateActualJson("ns1.cat.lol?key=value"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4", "STATUS", "active")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testIdnNameserver_works() {
    assertAboutJson()
        .that(generateActualJson("ns1.cat.みんな"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.みんな", "5-ROID")
                    .putAll("ADDRESSTYPE", "v6", "ADDRESS", "bad:f00d:cafe::15:beef")
                    .load("rdap_host_unicode.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testPunycodeNameserver_works() {
    assertAboutJson()
        .that(generateActualJson("ns1.cat.xn--q9jyb4c"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.みんな", "5-ROID")
                    .putAll("ADDRESSTYPE", "v6", "ADDRESS", "bad:f00d:cafe::15:beef")
                    .load("rdap_host_unicode.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testMultilevelNameserver_works() {
    assertAboutJson()
        .that(generateActualJson("ns1.domain.1.tld"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("ns1.domain.1.tld", "8-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "5.6.7.8", "STATUS", "active")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testExternalNameserver_works() {
    assertAboutJson()
        .that(generateActualJson("ns1.domain.external"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("ns1.domain.external", "C-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "9.10.11.12", "STATUS", "active")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testDeletedNameserver_notFound_includeDeletedNotSpecified() {
    generateActualJson("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedNameserver_notFound_includeDeletedSetFalse() {
    action.includeDeletedParam = Optional.of(false);
    generateActualJson("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedNameserver_notFound_notLoggedIn() {
    logout();
    action.includeDeletedParam = Optional.of(true);
    generateActualJson("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedNameserver_notFound_loggedInAsDifferentRegistrar() {
    login("otherregistrar");
    action.includeDeletedParam = Optional.of(true);
    generateActualJson("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedNameserver_found_loggedInAsCorrectRegistrar() {
    login("TheRegistrar");
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("nsdeleted.cat.lol"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("nsdeleted.cat.lol", "A-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4", "STATUS", "inactive")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testDeletedNameserver_found_loggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("nsdeleted.cat.lol"))
        .isEqualTo(
            addPermanentBoilerplateNotices(
                jsonFileBuilder()
                    .addNameserver("nsdeleted.cat.lol", "A-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4", "STATUS", "inactive")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testMetrics() {
    generateActualJson("ns1.cat.lol");
    verify(rdapMetrics)
        .updateMetrics(
            RdapMetrics.RdapMetricInformation.builder()
                .setEndpointType(EndpointType.NAMESERVER)
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
