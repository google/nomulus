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
import static google.registry.rdap.RdapTestHelper.loadJsonFile;
import static google.registry.rdap.RdapTestHelper.parseJsonObject;
import static google.registry.request.Action.Method.GET;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomain;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarPocs;
import static google.registry.testing.GsonSubject.assertAboutJson;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.testing.FakeResponse;
import google.registry.testing.FullFieldsTestEntityHelper;
import java.net.URLDecoder;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapNameserverSearchAction}. */
class RdapNameserverSearchActionTest extends RdapSearchActionTestCase<RdapNameserverSearchAction> {

  RdapNameserverSearchActionTest() {
    super(RdapNameserverSearchAction.class);
  }

  private Domain domainCatLol;
  private Host hostNs1CatLol;
  private Host hostNs2CatLol;

  private JsonObject generateActualJsonWithName(String name) {
    return generateActualJsonWithName(name, null);
  }

  private JsonObject generateActualJsonWithName(String name, String cursor) {
    metricSearchType = SearchType.BY_NAMESERVER_NAME;
    rememberWildcardType(name);
    action.nameParam = Optional.of(name);
    if (cursor == null) {
      action.parameterMap = ImmutableListMultimap.of("name", name);
      action.cursorTokenParam = Optional.empty();
    } else {
      action.parameterMap = ImmutableListMultimap.of("name", name, "cursor", cursor);
      action.cursorTokenParam = Optional.of(cursor);
    }
    action.run();
    return parseJsonObject(response.getPayload());
  }

  private JsonObject generateActualJsonWithIp(String ipString) {
    return generateActualJsonWithIp(ipString, null);
  }

  private JsonObject generateActualJsonWithIp(String ipString, String cursor) {
    metricSearchType = SearchType.BY_NAMESERVER_ADDRESS;
    action.parameterMap = ImmutableListMultimap.of("ip", ipString);
    action.ipParam = Optional.of(ipString);
    if (cursor == null) {
      action.parameterMap = ImmutableListMultimap.of("ip", ipString);
      action.cursorTokenParam = Optional.empty();
    } else {
      action.parameterMap = ImmutableListMultimap.of("ip", ipString, "cursor", cursor);
      action.cursorTokenParam = Optional.of(cursor);
    }
    action.run();
    return parseJsonObject(response.getPayload());
  }

  @BeforeEach
  void beforeEach() {
    // cat.lol and cat2.lol
    createTld("lol");
    Registrar registrar =
        persistResource(
            makeRegistrar("evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistResources(makeRegistrarPocs(registrar));
    hostNs1CatLol =
        FullFieldsTestEntityHelper.makeAndPersistHost(
            "ns1.cat.lol", "1.2.3.4", clock.nowUtc().minusYears(1));
    hostNs2CatLol =
        FullFieldsTestEntityHelper.makeAndPersistHost(
            "ns2.cat.lol", "bad:f00d:cafe::15:beef", clock.nowUtc().minusYears(1));
    FullFieldsTestEntityHelper.makeAndPersistHost(
        "ns1.cat2.lol", "1.2.3.3", "bad:f00d:cafe::15:beef", clock.nowUtc().minusYears(1));
    FullFieldsTestEntityHelper.makeAndPersistHost(
        "ns1.cat.external", null, null, clock.nowUtc().minusYears(1));

    // cat.みんな
    createTld("xn--q9jyb4c");
    registrar = persistResource(makeRegistrar("unicoderegistrar", "みんな", Registrar.State.ACTIVE));
    persistResources(makeRegistrarPocs(registrar));
    FullFieldsTestEntityHelper.makeAndPersistHost(
        "ns1.cat.みんな", "1.2.3.5", clock.nowUtc().minusYears(1));

    // cat.1.test
    createTld("1.test");
    registrar = persistResource(makeRegistrar("multiregistrar", "1.test", Registrar.State.ACTIVE));
    persistResources(makeRegistrarPocs(registrar));
    FullFieldsTestEntityHelper.makeAndPersistHost(
        "ns1.cat.1.test", "1.2.3.6", clock.nowUtc().minusYears(1));

    // create a domain so that we can use it as a test nameserver search string suffix
    domainCatLol =
        persistResource(
            makeDomain(
                    "cat.lol",
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372808-ERL", "Goblin Market", "lol@cat.lol", registrar)),
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372808-IRL", "Santa Claus", "BOFH@cat.lol", registrar)),
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372808-TRL", "The Raven", "bog@cat.lol", registrar)),
                    hostNs1CatLol,
                    hostNs2CatLol,
                    registrar)
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of("ns1.cat.lol", "ns2.cat.lol"))
                .build());
    persistResource(
        hostNs1CatLol.asBuilder().setSuperordinateDomain(domainCatLol.createVKey()).build());
    persistResource(
        hostNs2CatLol.asBuilder().setSuperordinateDomain(domainCatLol.createVKey()).build());

    action.ipParam = Optional.empty();
    action.nameParam = Optional.empty();
  }

  private JsonObject addBoilerplate(JsonObject jsonObject) {
    jsonObject = RdapTestHelper.wrapInSearchReply("nameserverSearchResults", jsonObject);
    return addPermanentBoilerplateNotices(jsonObject);
  }

  private void createManyHosts(int numHosts) {
    ImmutableList.Builder<Host> hostsBuilder = new ImmutableList.Builder<>();
    ImmutableSet.Builder<String> subordinateHostsBuilder = new ImmutableSet.Builder<>();
    for (int i = 1; i <= numHosts; i++) {
      String hostName = String.format("nsx%d.cat.lol", i);
      subordinateHostsBuilder.add(hostName);
      hostsBuilder.add(FullFieldsTestEntityHelper.makeHost(hostName, "5.5.5.1", "5.5.5.2"));
    }
    persistResources(hostsBuilder.build());
    domainCatLol =
        persistResource(
            domainCatLol.asBuilder().setSubordinateHosts(subordinateHostsBuilder.build()).build());
  }

  private void createDeletedHost() {
    persistResource(
        FullFieldsTestEntityHelper.makeAndPersistHost(
                "nsdeleted.cat.lol", "4.3.2.1", clock.nowUtc().minusYears(1))
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusMonths(1))
            .build());
  }

  private void verifyMetrics(
      long numHostsRetrieved, IncompletenessWarningType incompletenessWarningType) {
    verifyMetrics(Optional.of(numHostsRetrieved), incompletenessWarningType);
  }

  private void verifyMetrics(long numHostsRetrieved) {
    verifyMetrics(Optional.of(numHostsRetrieved), IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      Optional<Long> numHostsRetrieved, IncompletenessWarningType incompletenessWarningType) {
    verifyMetrics(
        EndpointType.NAMESERVERS,
        GET,
        action.includeDeletedParam.orElse(false),
        action.registrarParam.isPresent(),
        Optional.empty(),
        numHostsRetrieved,
        Optional.empty(),
        incompletenessWarningType);
  }

  private void verifyErrorMetrics() {
    metricStatusCode = 404;
    verifyMetrics(0);
  }

  private void verifyErrorMetrics(Optional<Long> numHostsRetrieved, int statusCode) {
    metricStatusCode = statusCode;
    verifyMetrics(numHostsRetrieved, IncompletenessWarningType.COMPLETE);
  }

  @Test
  void testInvalidPath_rejected() {
    action.requestPath = actionPath + "/path";
    action.run();
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(Optional.empty(), 400);
  }

  @Test
  void testInvalidRequest_rejected() {
    action.run();
    assertAboutJson()
        .that(parseJsonObject(response.getPayload()))
        .isEqualTo(generateExpectedJsonError("You must specify either name=XXXX or ip=YYYY", 400));
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(Optional.empty(), 400);
  }

  @Test
  void testInvalidSuffix_rejected() {
    assertAboutJson()
        .that(generateActualJsonWithName("exam*ple"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of a label, but"
                    + " was: 'exam*ple'",
                422));
    rememberWildcardTypeInvalid();
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testNonexistentDomainSuffix_unprocessable() {
    assertAboutJson()
        .that(generateActualJsonWithName("exam*.foo.bar"))
        .isEqualTo(
            generateExpectedJsonError(
                "A suffix after a wildcard in a nameserver lookup must be an in-bailiwick domain",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testMultipleWildcards_rejected() {
    assertAboutJson()
        .that(generateActualJsonWithName("*.*"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of a label, but"
                    + " was: '*.*'",
                422));
    rememberWildcardTypeInvalid();
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testNoCharactersToMatch_rejected() {
    assertAboutJson()
        .that(generateActualJsonWithName("*"))
        .isEqualTo(
            generateExpectedJsonError("Initial search string must be at least 2 characters", 422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testFewerThanTwoCharactersToMatch_rejected() {
    assertAboutJson()
        .that(generateActualJsonWithName("a*"))
        .isEqualTo(
            generateExpectedJsonError("Initial search string must be at least 2 characters", 422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testNameMatch_ns1_cat_lol_found() {
    assertAboutJson()
        .that(generateActualJsonWithName("ns1.cat.lol"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4")
                    .load("rdap_host_linked.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_cat_lol_foundWithUpperCase() {
    assertAboutJson()
        .that(generateActualJsonWithName("Ns1.CaT.lOl"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4")
                    .load("rdap_host_linked.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_cat_lol_found_sameRegistrarRequested() {
    action.registrarParam = Optional.of("TheRegistrar");
    generateActualJsonWithName("ns1.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_cat_lol_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("unicoderegistrar");
    generateActualJsonWithName("ns1.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics(Optional.of(1L), 404);
  }

  @Test
  void testNameMatch_ns2_cat_lol_found() {
    assertAboutJson()
        .that(generateActualJsonWithName("ns2.cat.lol"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns2.cat.lol", "4-ROID")
                    .putAll("ADDRESSTYPE", "v6", "ADDRESS", "bad:f00d:cafe::15:beef")
                    .load("rdap_host_linked.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_cat2_lol_found() {
    // ns1.cat2.lol has two IP addresses; just test that we are able to find it
    generateActualJsonWithName("ns1.cat2.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_cat_external_found() {
    assertAboutJson()
        .that(generateActualJsonWithName("ns1.cat.external"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.external", "8-ROID")
                    .load("rdap_host_external.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_cat_idn_unicode_found() {
    assertAboutJson()
        .that(generateActualJsonWithName("ns1.cat.みんな"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.みんな", "B-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.5")
                    .load("rdap_host_unicode.json")));
    metricWildcardType = WildcardType.NO_WILDCARD;
    metricPrefixLength = 19;
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_cat_idn_punycode_found() {
    assertAboutJson()
        .that(generateActualJsonWithName("ns1.cat.xn--q9jyb4c"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.みんな", "B-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.5")
                    .load("rdap_host_unicode.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_cat_1_test_found() {
    assertAboutJson()
        .that(generateActualJsonWithName("ns1.cat.1.test"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.1.test", "E-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.6", "STATUS", "active")
                    .load("rdap_host.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_nsstar_cat_lol_found() {
    generateActualJsonWithName("ns*.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(2);
  }

  @Test
  void testNameMatch_nsstar_cat_lol_found_sameRegistrarRequested() {
    action.registrarParam = Optional.of("TheRegistrar");
    generateActualJsonWithName("ns*.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(2);
  }

  @Test
  void testNameMatch_nsstar_cat_lol_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("unicoderegistrar");
    generateActualJsonWithName("ns*.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics(Optional.of(2L), 404);
  }

  @Test
  void testNameMatch_nstar_cat_lol_found() {
    generateActualJsonWithName("n*.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(2);
  }

  @Test
  void testNameMatch_star_cat_lol_found() {
    generateActualJsonWithName("*.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(2);
  }

  @Test
  void testNameMatch_star_cat_lol_found_sameRegistrarRequested() {
    action.registrarParam = Optional.of("TheRegistrar");
    generateActualJsonWithName("*.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(2);
  }

  @Test
  void testNameMatch_star_cat_lol_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("unicoderegistrar");
    generateActualJsonWithName("*.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics(Optional.of(2L), 404);
  }

  @Test
  void testNameMatch_nsstar_found() {
    generateActualJsonWithName("ns*");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(5, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatch_ns1_cat_lstar_found() {
    generateActualJsonWithName("ns1.cat.l*");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatch_ns1_castar_found() {
    generateActualJsonWithName("ns1.ca*");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(5, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatch_dogstar_notFound() {
    generateActualJsonWithName("dog*");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testNameMatch_nontruncatedResultSet() {
    createManyHosts(4);
    assertAboutJson()
        .that(generateActualJsonWithName("nsx*.cat.lol"))
        .isEqualTo(loadJsonFile("rdap_nontruncated_hosts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(4);
  }

  @Test
  void testNameMatch_truncatedResultSet() {
    createManyHosts(5);
    assertAboutJson()
        .that(generateActualJsonWithName("nsx*.cat.lol"))
        .isEqualTo(
            loadJsonFile(
                "rdap_truncated_hosts.json", "QUERY", "name=nsx*.cat.lol&cursor=bnN4NC5jYXQubG9s"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(5, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatch_reallyTruncatedResultSet() {
    createManyHosts(9);
    assertAboutJson()
        .that(generateActualJsonWithName("nsx*.cat.lol"))
        .isEqualTo(
            loadJsonFile(
                "rdap_truncated_hosts.json", "QUERY", "name=nsx*.cat.lol&cursor=bnN4NC5jYXQubG9s"));
    assertThat(response.getStatus()).isEqualTo(200);
    // When searching names, we look for additional matches, in case some are not visible.
    verifyMetrics(9, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatchDeletedHost_foundTheOtherHost() {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    assertAboutJson()
        .that(generateActualJsonWithName("ns*.cat.lol"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns2.cat.lol", "4-ROID")
                    .putAll("ADDRESSTYPE", "v6", "ADDRESS", "bad:f00d:cafe::15:beef")
                    .load("rdap_host_linked.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(2);
  }

  @Test
  void testNameMatchDeletedHost_notFound() {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    assertAboutJson()
        .that(generateActualJsonWithName("ns1.cat.lol"))
        .isEqualTo(generateExpectedJsonError("No nameservers found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchDeletedHostWithWildcard_notFound() {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    assertAboutJson()
        .that(generateActualJsonWithName("cat.lo*"))
        .isEqualTo(generateExpectedJsonError("No nameservers found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchDeleted_notFound_includeDeletedNotSpecified() {
    createDeletedHost();
    generateActualJsonWithName("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchDeleted_notFound_notLoggedIn() {
    createDeletedHost();
    action.includeDeletedParam = Optional.of(true);
    generateActualJsonWithName("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchDeleted_notFound_loggedInAsDifferentRegistrar() {
    createDeletedHost();
    action.includeDeletedParam = Optional.of(true);
    login("unicoderegistrar");
    generateActualJsonWithName("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics(Optional.of(1L), 404);
  }

  @Test
  void testNameMatchDeleted_found_loggedInAsCorrectRegistrar() {
    createDeletedHost();
    action.includeDeletedParam = Optional.of(true);
    login("TheRegistrar");
    generateActualJsonWithName("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatchDeleted_found_loggedInAsAdmin() {
    createDeletedHost();
    action.includeDeletedParam = Optional.of(true);
    loginAsAdmin();
    generateActualJsonWithName("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatchDeleted_found_loggedInAndRequestingSameRegistrar() {
    createDeletedHost();
    action.registrarParam = Optional.of("TheRegistrar");
    action.includeDeletedParam = Optional.of(true);
    login("TheRegistrar");
    generateActualJsonWithName("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testNameMatchDeleted_notFound_loggedInButRequestingDifferentRegistrar() {
    createDeletedHost();
    action.registrarParam = Optional.of("unicoderegistrar");
    action.includeDeletedParam = Optional.of(true);
    login("TheRegistrar");
    generateActualJsonWithName("nsdeleted.cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  /**
   * Checks multi-page result set navigation using the cursor.
   *
   * <p>If there are more results than the max result set size, the RDAP code returns a cursor token
   * which can be used in a subsequent call to get the next chunk of results.
   *
   * @param byName true if we are searching by name; false if we are searching by address
   * @param paramValue the query string
   * @param expectedNames an immutable list of the host names we expect to retrieve
   */
  private void checkCursorNavigation(
      boolean byName, String paramValue, ImmutableList<String> expectedNames) throws Exception {
    String cursor = null;
    int expectedNameOffset = 0;
    int expectedPageCount =
        (expectedNames.size() + action.rdapResultSetMaxSize - 1) / action.rdapResultSetMaxSize;
    for (int pageNum = 0; pageNum < expectedPageCount; pageNum++) {
      JsonObject results =
          byName
              ? generateActualJsonWithName(paramValue, cursor)
              : generateActualJsonWithIp(paramValue, cursor);
      assertThat(response.getStatus()).isEqualTo(200);
      String linkToNext = RdapTestHelper.getLinkToNext(results);
      if (pageNum == expectedPageCount - 1) {
        assertThat(linkToNext).isNull();
      } else {
        assertThat(linkToNext).isNotNull();
        int pos = linkToNext.indexOf("cursor=");
        assertThat(pos).isAtLeast(0);
        cursor = URLDecoder.decode(linkToNext.substring(pos + 7), "UTF-8");
        JsonArray searchResults = results.getAsJsonArray("nameserverSearchResults");
        assertThat(searchResults).hasSize(action.rdapResultSetMaxSize);
        for (JsonElement item : searchResults) {
          assertThat(item.getAsJsonObject().get("ldhName").getAsString())
              .isEqualTo(expectedNames.get(expectedNameOffset++));
        }
        response = new FakeResponse();
        action.response = response;
      }
    }
  }

  @Test
  void testNameMatch_cursorNavigationWithSuperordinateDomain() throws Exception {
    createManyHosts(9);
    checkCursorNavigation(
        true,
        "ns*.cat.lol",
        ImmutableList.of(
            "nsx1.cat.lol",
            "nsx2.cat.lol",
            "nsx3.cat.lol",
            "nsx4.cat.lol",
            "nsx5.cat.lol",
            "nsx6.cat.lol",
            "nsx7.cat.lol",
            "nsx8.cat.lol",
            "nsx9.cat.lol"));
  }

  @Test
  void testNameMatch_cursorNavigationWithPrefix_sql() throws Exception {
    createManyHosts(9);
    checkCursorNavigation(
        true,
        "ns*",
        ImmutableList.of(
            "ns1.cat.1.test",
            "ns1.cat.external",
            "ns1.cat.lol",
            "ns1.cat.xn--q9jyb4c",
            "ns1.cat2.lol",
            "ns2.cat.lol",
            "nsx1.cat.lol",
            "nsx2.cat.lol",
            "nsx3.cat.lol",
            "nsx4.cat.lol",
            "nsx5.cat.lol",
            "nsx6.cat.lol",
            "nsx7.cat.lol",
            "nsx8.cat.lol",
            "nsx9.cat.lol"));
  }

  @Test
  void testAddressMatch_invalidAddress() {
    generateActualJsonWithIp("It is to laugh");
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(Optional.empty(), 400);
  }

  @Test
  void testAddressMatchV4Address_found() {
    assertAboutJson()
        .that(generateActualJsonWithIp("1.2.3.4"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .putAll("ADDRESSTYPE", "v4", "ADDRESS", "1.2.3.4")
                    .load("rdap_host_linked.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testAddressMatchV4Address_found_sameRegistrarRequested() {
    action.registrarParam = Optional.of("TheRegistrar");
    generateActualJsonWithIp("1.2.3.4");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testAddressMatchV4Address_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("unicoderegistrar");
    generateActualJsonWithIp("1.2.3.4");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testAddressMatchV6Address_foundMultiple() {
    assertAboutJson()
        .that(generateActualJsonWithIp("bad:f00d:cafe::15:beef"))
        .isEqualTo(loadJsonFile("rdap_multiple_hosts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(2);
  }

  @Test
  void testAddressMatchLocalhost_notFound() {
    generateActualJsonWithIp("127.0.0.1");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testAddressMatchDeletedHost_notFound() {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    assertAboutJson()
        .that(generateActualJsonWithIp("1.2.3.4"))
        .isEqualTo(generateExpectedJsonError("No nameservers found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testAddressMatch_nontruncatedResultSet() {
    createManyHosts(4);
    assertAboutJson()
        .that(generateActualJsonWithIp("5.5.5.1"))
        .isEqualTo(loadJsonFile("rdap_nontruncated_hosts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(4);
  }

  @Test
  void testAddressMatch_truncatedResultSet() {
    createManyHosts(5);
    assertAboutJson()
        .that(generateActualJsonWithIp("5.5.5.1"))
        .isEqualTo(
            loadJsonFile(
                "rdap_truncated_hosts.json", "QUERY", "ip=5.5.5.1&cursor=MTctUk9JRA%3D%3D"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(5, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testAddressMatch_reallyTruncatedResultSet() {
    createManyHosts(9);
    assertAboutJson()
        .that(generateActualJsonWithIp("5.5.5.1"))
        .isEqualTo(
            loadJsonFile(
                "rdap_truncated_hosts.json", "QUERY", "ip=5.5.5.1&cursor=MTctUk9JRA%3D%3D"));
    assertThat(response.getStatus()).isEqualTo(200);
    // When searching by address and not including deleted, we don't need to search for extra
    // matches.
    verifyMetrics(5, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testAddressMatchDeleted_notFound_includeDeletedNotSpecified() {
    createDeletedHost();
    generateActualJsonWithIp("4.3.2.1");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testAddressMatchDeleted_notFound_notLoggedIn() {
    createDeletedHost();
    action.includeDeletedParam = Optional.of(true);
    generateActualJsonWithIp("4.3.2.1");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testAddressMatchDeleted_notFound_loggedInAsDifferentRegistrar() {
    createDeletedHost();
    action.includeDeletedParam = Optional.of(true);
    login("unicoderegistrar");
    generateActualJsonWithIp("4.3.2.1");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics(Optional.of(1L), 404);
  }

  @Test
  void testAddressMatchDeleted_found_loggedInAsCorrectRegistrar() {
    createDeletedHost();
    action.includeDeletedParam = Optional.of(true);
    login("TheRegistrar");
    generateActualJsonWithIp("4.3.2.1");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testAddressMatchDeleted_found_loggedInAsAdmin() {
    createDeletedHost();
    action.includeDeletedParam = Optional.of(true);
    loginAsAdmin();
    generateActualJsonWithIp("4.3.2.1");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testAddressMatchDeleted_found_loggedInAndRequestingSameRegisrar() {
    createDeletedHost();
    action.registrarParam = Optional.of("TheRegistrar");
    action.includeDeletedParam = Optional.of(true);
    login("TheRegistrar");
    generateActualJsonWithIp("4.3.2.1");
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(1);
  }

  @Test
  void testAddressMatchDeleted_notFound_loggedButRequestingDiffentRegistrar() {
    createDeletedHost();
    action.registrarParam = Optional.of("unicoderegistrar");
    action.includeDeletedParam = Optional.of(true);
    login("TheRegistrar");
    generateActualJsonWithIp("4.3.2.1");
    assertThat(response.getStatus()).isEqualTo(404);
    verifyErrorMetrics();
  }

  @Test
  void testAddressMatch_cursorNavigation() throws Exception {
    createManyHosts(9);
    checkCursorNavigation(
        false,
        "5.5.5.1",
        ImmutableList.of(
            "nsx1.cat.lol",
            "nsx2.cat.lol",
            "nsx3.cat.lol",
            "nsx4.cat.lol",
            "nsx5.cat.lol",
            "nsx6.cat.lol",
            "nsx7.cat.lol",
            "nsx8.cat.lol",
            "nsx9.cat.lol"));
  }
}
