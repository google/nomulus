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
import static google.registry.rdap.RdapTestHelper.assertThat;
import static google.registry.rdap.RdapTestHelper.loadJsonFile;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistHostResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonObject;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import java.util.Optional;
import javax.annotation.Nullable;
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
    makeAndPersistHostResource(
        "ns1.cat.lol", "1.2.3.4", clock.nowUtc().minusYears(1));
    // idn
    createTld("xn--q9jyb4c");
    makeAndPersistHostResource(
        "ns1.cat.xn--q9jyb4c", "bad:f00d:cafe:0:0:0:15:beef", clock.nowUtc().minusYears(1));
    // multilevel
    createTld("1.tld");
    makeAndPersistHostResource(
        "ns1.domain.1.tld", "5.6.7.8", clock.nowUtc().minusYears(1));
    // deleted
    persistResource(
        makeAndPersistHostResource("nsdeleted.cat.lol", "1.2.3.4", clock.nowUtc().minusYears(1))
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusMonths(1))
            .build());
    // other registrar
    persistResource(
        makeRegistrar("otherregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE, 102L));
    // external
    makeAndPersistHostResource("ns1.domain.external", "9.10.11.12", clock.nowUtc().minusYears(1));
  }

  private JsonObject generateExpectedJson(
      String name,
      @Nullable ImmutableMap<String, String> otherSubstitutions,
      String expectedOutputFile) {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    builder.put("TYPE", "nameserver");
    builder.put("NAME", name);
    boolean punycodeSet = false;
    if (otherSubstitutions != null) {
      builder.putAll(otherSubstitutions);
      if (otherSubstitutions.containsKey("PUNYCODENAME")) {
        punycodeSet = true;
      }
    }
    if (!punycodeSet) {
      builder.put("PUNYCODENAME", name);
    }
    return loadJsonFile(
        expectedOutputFile,
        builder.build());
  }

  private JsonObject generateExpectedJsonWithTopLevelEntries(
      String name,
      @Nullable ImmutableMap<String, String> otherSubstitutions,
      String expectedOutputFile) {
    JsonObject obj = generateExpectedJson(name, otherSubstitutions, expectedOutputFile);
    RdapTestHelper.addNonDomainBoilerplateNotices(obj, "https://example.tld/rdap/");
    return obj;
  }

  @Test
  void testInvalidNameserver_returns400() {
    assertThat(generateActualJson("invalid/host/name"))
        .isEqualTo(
            generateExpectedJsonError(
                "invalid/host/name is not a valid nameserver: Invalid host name",
                400));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testUnknownNameserver_returns404() {
    assertThat(generateActualJson("ns1.missing.com")).isEqualTo(
        generateExpectedJsonError("ns1.missing.com not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testValidNameserver_works() {
    assertThat(generateActualJson("ns1.cat.lol"))
        .isEqualTo(generateExpectedJsonWithTopLevelEntries(
            "ns1.cat.lol",
            ImmutableMap.of(
                "HANDLE", "2-ROID",
                "ADDRESSTYPE", "v4",
                "ADDRESS", "1.2.3.4",
                "STATUS", "active"),
            "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testTrailingDot_getsIgnored() {
    assertThat(generateActualJson("ns1.cat.lol."))
        .isEqualTo(generateExpectedJsonWithTopLevelEntries(
            "ns1.cat.lol",
            ImmutableMap.of(
                "HANDLE", "2-ROID",
                "ADDRESSTYPE", "v4",
                "ADDRESS", "1.2.3.4",
                "STATUS", "active"),
            "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testUpperCase_getsCanonicalized() {
    assertThat(generateActualJson("Ns1.CaT.lOl."))
        .isEqualTo(generateExpectedJsonWithTopLevelEntries(
            "ns1.cat.lol",
            ImmutableMap.of(
                "HANDLE", "2-ROID",
                "ADDRESSTYPE", "v4",
                "ADDRESS", "1.2.3.4",
                "STATUS", "active"),
            "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testQueryParameter_getsIgnored() {
    assertThat(generateActualJson("ns1.cat.lol?key=value"))
        .isEqualTo(generateExpectedJsonWithTopLevelEntries(
            "ns1.cat.lol",
            ImmutableMap.of(
                "HANDLE", "2-ROID",
                "ADDRESSTYPE", "v4",
                "ADDRESS", "1.2.3.4",
                "STATUS", "active"),
            "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testIdnNameserver_works() {
    assertThat(generateActualJson("ns1.cat.みんな"))
        .isEqualTo(generateExpectedJsonWithTopLevelEntries(
            "ns1.cat.みんな",
            ImmutableMap.of(
                "PUNYCODENAME", "ns1.cat.xn--q9jyb4c",
                "HANDLE", "5-ROID",
                "ADDRESSTYPE", "v6",
                "ADDRESS", "bad:f00d:cafe::15:beef",
                "STATUS", "active"),
            "rdap_host_unicode.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testPunycodeNameserver_works() {
    assertThat(generateActualJson("ns1.cat.xn--q9jyb4c"))
        .isEqualTo(generateExpectedJsonWithTopLevelEntries(
            "ns1.cat.みんな",
            ImmutableMap.of(
                "PUNYCODENAME", "ns1.cat.xn--q9jyb4c",
                "HANDLE", "5-ROID",
                "ADDRESSTYPE", "v6",
                "ADDRESS", "bad:f00d:cafe::15:beef",
                "STATUS", "active"),
            "rdap_host_unicode.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testMultilevelNameserver_works() {
    assertThat(generateActualJson("ns1.domain.1.tld"))
        .isEqualTo(generateExpectedJsonWithTopLevelEntries(
            "ns1.domain.1.tld",
            ImmutableMap.of(
                "HANDLE", "8-ROID",
                "ADDRESSTYPE", "v4",
                "ADDRESS", "5.6.7.8",
                "STATUS", "active"),
            "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testExternalNameserver_works() {
    assertThat(generateActualJson("ns1.domain.external"))
        .isEqualTo(generateExpectedJsonWithTopLevelEntries(
            "ns1.domain.external",
            ImmutableMap.of(
                "HANDLE", "C-ROID",
                "ADDRESSTYPE", "v4",
                "ADDRESS", "9.10.11.12",
                "STATUS", "active"),
            "rdap_host.json"));
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
    assertThat(generateActualJson("nsdeleted.cat.lol"))
        .isEqualTo(
            generateExpectedJsonWithTopLevelEntries(
                "nsdeleted.cat.lol",
                ImmutableMap.of(
                    "HANDLE", "A-ROID",
                    "ADDRESSTYPE", "v4",
                    "ADDRESS", "1.2.3.4",
                    "STATUS", "inactive"),
                "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testDeletedNameserver_found_loggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    assertThat(generateActualJson("nsdeleted.cat.lol"))
        .isEqualTo(
            generateExpectedJsonWithTopLevelEntries(
                "nsdeleted.cat.lol",
                ImmutableMap.of(
                    "HANDLE", "A-ROID",
                    "ADDRESSTYPE", "v4",
                    "ADDRESS", "1.2.3.4",
                    "STATUS", "inactive"),
                "rdap_host.json"));
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
