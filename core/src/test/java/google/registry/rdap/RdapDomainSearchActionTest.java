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
import static google.registry.rdap.RdapTestHelper.parseJsonObject;
import static google.registry.request.Action.Method.POST;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistDomainAsDeleted;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistResources;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistHostResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomainBase;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.Period;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.testing.FakeResponse;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Optional;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdapDomainSearchAction}. */
@RunWith(JUnit4.class)
public class RdapDomainSearchActionTest extends RdapSearchActionTestCase<RdapDomainSearchAction> {

  public RdapDomainSearchActionTest() {
    super(RdapDomainSearchAction.class);
  }

  private Registrar registrar;
  private DomainBase domainCatLol;
  private DomainBase domainCatLol2;
  private DomainBase domainCatExample;
  private DomainBase domainIdn;
  private DomainBase domainMultipart;
  private ContactResource contact1;
  private ContactResource contact2;
  private ContactResource contact3;
  private HostResource hostNs1CatLol;
  private HostResource hostNs2CatLol;
  private HashMap<String, HostResource> hostNameToHostMap = new HashMap<>();

  enum RequestType {
    NONE,
    NAME,
    NS_LDH_NAME,
    NS_IP
  }

  private JsonObject generateActualJson(RequestType requestType, String paramValue) {
    return generateActualJson(requestType, paramValue, null);
  }

  private JsonObject generateActualJson(RequestType requestType, String paramValue, String cursor) {
    action.requestPath = actionPath;
    action.requestMethod = POST;
    String requestTypeParam = null;
    switch (requestType) {
      case NAME:
        action.nameParam = Optional.of(paramValue);
        action.nsLdhNameParam = Optional.empty();
        action.nsIpParam = Optional.empty();
        requestTypeParam = "name";
        break;
      case NS_LDH_NAME:
        action.nameParam = Optional.empty();
        action.nsLdhNameParam = Optional.of(paramValue);
        action.nsIpParam = Optional.empty();
        requestTypeParam = "nsLdhName";
        break;
      case NS_IP:
        action.nameParam = Optional.empty();
        action.nsLdhNameParam = Optional.empty();
        action.nsIpParam = Optional.of(paramValue);
        requestTypeParam = "nsIp";
        break;
      default:
        action.nameParam = Optional.empty();
        action.nsLdhNameParam = Optional.empty();
        action.nsIpParam = Optional.empty();
        requestTypeParam = "";
        break;
    }
    if (paramValue != null) {
      if (cursor == null) {
        action.parameterMap = ImmutableListMultimap.of(requestTypeParam, paramValue);
        action.cursorTokenParam = Optional.empty();
      } else {
        action.parameterMap =
            ImmutableListMultimap.of(requestTypeParam, paramValue, "cursor", cursor);
        action.cursorTokenParam = Optional.of(cursor);
      }
    }
    action.run();
    return parseJsonObject(response.getPayload());
  }

  private HostResource addHostToMap(HostResource host) {
    hostNameToHostMap.put(host.getFullyQualifiedHostName(), host);
    return host;
  }

  @Before
  public void setUp() {
    RdapDomainSearchAction.maxNameserversInFirstStage = 40;

    // lol
    createTld("lol");
    registrar =
        persistResource(
            makeRegistrar("evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    contact1 =
        makeAndPersistContactResource(
            "5372808-ERL", "Goblin Market", "lol@cat.lol", clock.nowUtc().minusYears(1), registrar);
    contact2 =
        makeAndPersistContactResource(
            "5372808-IRL", "Santa Claus", "BOFH@cat.lol", clock.nowUtc().minusYears(2), registrar);
    contact3 =
        makeAndPersistContactResource(
            "5372808-TRL", "The Raven", "bog@cat.lol", clock.nowUtc().minusYears(3), registrar);
    hostNs1CatLol =
        addHostToMap(
            makeAndPersistHostResource("ns1.cat.lol", "1.2.3.4", clock.nowUtc().minusYears(1)));
    hostNs2CatLol =
        addHostToMap(
            makeAndPersistHostResource(
                "ns2.cat.lol", "bad:f00d:cafe:0:0:0:15:beef", clock.nowUtc().minusYears(2)));
    domainCatLol =
        persistResource(
            makeDomainBase(
                    "cat.lol",
                    contact1,
                    contact2,
                    contact3,
                    hostNs1CatLol,
                    hostNs2CatLol,
                    registrar)
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of("ns1.cat.lol", "ns2.cat.lol"))
                .setCreationTimeForTest(clock.nowUtc().minusYears(3))
                .setCreationClientId("foo")
                .build());
    persistResource(
        hostNs1CatLol.asBuilder().setSuperordinateDomain(domainCatLol.createVKey()).build());
    persistResource(
        hostNs2CatLol.asBuilder().setSuperordinateDomain(domainCatLol.createVKey()).build());

    domainCatLol2 =
        persistResource(
            makeDomainBase(
                    "cat2.lol",
                    makeAndPersistContactResource(
                        "6372808-ERL",
                        "Siegmund",
                        "siegmund@cat2.lol",
                        clock.nowUtc().minusYears(1),
                        registrar),
                    makeAndPersistContactResource(
                        "6372808-IRL",
                        "Sieglinde",
                        "sieglinde@cat2.lol",
                        clock.nowUtc().minusYears(2),
                        registrar),
                    makeAndPersistContactResource(
                        "6372808-TRL",
                        "Siegfried",
                        "siegfried@cat2.lol",
                        clock.nowUtc().minusYears(3),
                        registrar),
                    addHostToMap(
                        makeAndPersistHostResource(
                            "ns1.cat.example", "10.20.30.40", clock.nowUtc().minusYears(1))),
                    addHostToMap(
                        makeAndPersistHostResource(
                            "ns2.dog.lol",
                            "12:feed:5000:0:0:0:15:beef",
                            clock.nowUtc().minusYears(2))),
                    registrar)
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc().minusYears(3))
                .setCreationClientId("foo")
                .build());
    // cat.example
    createTld("example");
    registrar =
        persistResource(
            makeRegistrar("goodregistrar", "St. John Chrysostom", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    domainCatExample =
        persistResource(
            makeDomainBase(
                    "cat.example",
                    makeAndPersistContactResource(
                        "7372808-ERL",
                        "Matthew",
                        "lol@cat.lol",
                        clock.nowUtc().minusYears(1),
                        registrar),
                    makeAndPersistContactResource(
                        "7372808-IRL",
                        "Mark",
                        "BOFH@cat.lol",
                        clock.nowUtc().minusYears(2),
                        registrar),
                    makeAndPersistContactResource(
                        "7372808-TRL",
                        "Luke",
                        "bog@cat.lol",
                        clock.nowUtc().minusYears(3),
                        registrar),
                    hostNs1CatLol,
                    addHostToMap(
                        makeAndPersistHostResource(
                            "ns2.external.tld",
                            "bad:f00d:cafe:0:0:0:16:beef",
                            clock.nowUtc().minusYears(2))),
                    registrar)
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc().minusYears(3))
                .setCreationClientId("foo")
                .build());
    // cat.みんな
    createTld("xn--q9jyb4c");
    registrar = persistResource(makeRegistrar("unicoderegistrar", "みんな", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    domainIdn =
        persistResource(
            makeDomainBase(
                    "cat.みんな",
                    makeAndPersistContactResource(
                        "8372808-ERL",
                        "(◕‿◕)",
                        "lol@cat.みんな",
                        clock.nowUtc().minusYears(1),
                        registrar),
                    makeAndPersistContactResource(
                        "8372808-IRL",
                        "Santa Claus",
                        "BOFH@cat.みんな",
                        clock.nowUtc().minusYears(2),
                        registrar),
                    makeAndPersistContactResource(
                        "8372808-TRL",
                        "The Raven",
                        "bog@cat.みんな",
                        clock.nowUtc().minusYears(3),
                        registrar),
                    addHostToMap(
                        makeAndPersistHostResource(
                            "ns1.cat.みんな", "1.2.3.5", clock.nowUtc().minusYears(1))),
                    addHostToMap(
                        makeAndPersistHostResource(
                            "ns2.cat.みんな",
                            "bad:f00d:cafe:0:0:0:14:beef",
                            clock.nowUtc().minusYears(2))),
                    registrar)
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc().minusYears(3))
                .setCreationClientId("foo")
                .build());
    // cat.1.test
    createTld("1.test");
    registrar = persistResource(makeRegistrar("multiregistrar", "1.test", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    domainMultipart =
        persistResource(
            makeDomainBase(
                    "cat.1.test",
                    makeAndPersistContactResource(
                        "9372808-ERL",
                        "(◕‿◕)",
                        "lol@cat.みんな",
                        clock.nowUtc().minusYears(1),
                        registrar),
                    makeAndPersistContactResource(
                        "9372808-IRL",
                        "Santa Claus",
                        "BOFH@cat.みんな",
                        clock.nowUtc().minusYears(2),
                        registrar),
                    makeAndPersistContactResource(
                        "9372808-TRL",
                        "The Raven",
                        "bog@cat.みんな",
                        clock.nowUtc().minusYears(3),
                        registrar),
                    addHostToMap(
                        makeAndPersistHostResource(
                            "ns1.cat.1.test", "1.2.3.5", clock.nowUtc().minusYears(1))),
                    addHostToMap(
                        makeAndPersistHostResource(
                            "ns2.cat.2.test",
                            "bad:f00d:cafe:0:0:0:14:beef",
                            clock.nowUtc().minusYears(2))),
                    registrar)
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of("ns1.cat.1.test"))
                .setCreationTimeForTest(clock.nowUtc().minusYears(3))
                .setCreationClientId("foo")
                .build());

    persistResource(makeRegistrar("otherregistrar", "other", Registrar.State.ACTIVE));

    // history entries
    persistResource(
        makeHistoryEntry(
            domainCatLol,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    persistResource(
        makeHistoryEntry(
            domainCatLol2,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    persistResource(
        makeHistoryEntry(
            domainCatExample,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    persistResource(
        makeHistoryEntry(
            domainIdn,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));
    persistResource(
        makeHistoryEntry(
            domainMultipart,
            HistoryEntry.Type.DOMAIN_CREATE,
            Period.create(1, Period.Unit.YEARS),
            "created",
            clock.nowUtc()));

    action.requestMethod = POST;
  }

  private JsonObject generateExpectedJsonForTwoDomainsNsReply() {
    return jsonFileBuilder()
        .addDomain("cat.example", "21-EXAMPLE")
        .addDomain("cat.lol", "C-LOL")
        .load("rdap_domains_two.json");
  }

  private JsonObject generateExpectedJsonForTwoDomainsCatStarReply() {
    return jsonFileBuilder()
        .addDomain("cat.lol", "C-LOL")
        .addDomain("cat2.lol", "17-LOL")
        .load("rdap_domains_two.json");
  }

  private void deleteCatLol() {
    persistResource(
        domainCatLol.asBuilder().setDeletionTime(clock.nowUtc().minusMonths(6)).build());
    persistResource(
        makeHistoryEntry(
            domainCatLol,
            HistoryEntry.Type.DOMAIN_DELETE,
            Period.create(1, Period.Unit.YEARS),
            "deleted",
            clock.nowUtc().minusMonths(6)));
  }

  private void createManyDomainsAndHosts(
      int numActiveDomains, int numTotalDomainsPerActiveDomain, int numHosts) {
    ImmutableSet.Builder<VKey<HostResource>> hostKeysBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<String> subordinateHostnamesBuilder = new ImmutableSet.Builder<>();
    String mainDomainName = String.format("domain%d.lol", numTotalDomainsPerActiveDomain);
    for (int i = numHosts; i >= 1; i--) {
      String hostName = String.format("ns%d.%s", i, mainDomainName);
      subordinateHostnamesBuilder.add(hostName);
      HostResource host =
          makeAndPersistHostResource(
              hostName,
              String.format("5.5.%d.%d", 5 + i / 250, i % 250),
              clock.nowUtc().minusYears(1));
      hostKeysBuilder.add(host.createVKey());
    }
    ImmutableSet<VKey<HostResource>> hostKeys = hostKeysBuilder.build();
    // Create all the domains at once, then persist them in parallel, for increased efficiency.
    ImmutableList.Builder<DomainBase> domainsBuilder = new ImmutableList.Builder<>();
    for (int i = numActiveDomains * numTotalDomainsPerActiveDomain; i >= 1; i--) {
      String domainName = String.format("domain%d.lol", i);
      DomainBase.Builder builder =
          makeDomainBase(domainName, contact1, contact2, contact3, null, null, registrar)
              .asBuilder()
              .setNameservers(hostKeys)
              .setCreationTimeForTest(clock.nowUtc().minusYears(3))
              .setCreationClientId("foo");
      if (domainName.equals(mainDomainName)) {
        builder.setSubordinateHosts(subordinateHostnamesBuilder.build());
      }
      if (i % numTotalDomainsPerActiveDomain != 0) {
        builder = builder.setDeletionTime(clock.nowUtc().minusDays(1));
      }
      domainsBuilder.add(builder.build());
    }
    persistResources(domainsBuilder.build());
  }

  private void checkNumberOfDomainsInResult(JsonObject obj, int expected) {
    assertThat(obj.getAsJsonArray("domainSearchResults")).hasSize(expected);
  }

  private void runSuccessfulTestWithCatLol(
      RequestType requestType, String queryString, String filename) {
    runSuccessfulTest(
        requestType,
        queryString,
        jsonFileBuilder()
            .addDomain("cat.lol", "C-LOL")
            .addRegistrar("Yes Virginia <script>")
            .addNameserver("ns1.cat.lol", "8-ROID")
            .addNameserver("ns2.cat.lol", "A-ROID")
            .addContact("4-ROID")
            .addContact("6-ROID")
            .addContact("2-ROID")
            .setNextQuery(queryString)
            .load(filename));
  }

  private void runSuccessfulTestWithCat2Lol(
      RequestType requestType, String queryString, String filename) {
    runSuccessfulTest(
        requestType,
        queryString,
        jsonFileBuilder()
            .addDomain("cat2.lol", "17-LOL")
            .addRegistrar("Yes Virginia <script>")
            .addNameserver("ns1.cat.example", "13-ROID")
            .addNameserver("ns2.dog.lol", "15-ROID")
            .addContact("F-ROID")
            .addContact("11-ROID")
            .addContact("D-ROID")
            .setNextQuery(queryString)
            .load(filename));
  }

  private JsonObject wrapInSearchReply(JsonObject obj) {
    obj = RdapTestHelper.wrapInSearchReply("domainSearchResults", obj);
    RdapTestHelper.addDomainBoilerplateNotices(obj, "https://example.tld/rdap/");
    return obj;
  }

  private void runSuccessfulTest(RequestType requestType, String queryString, JsonObject expected) {
    rememberWildcardType(queryString);
    assertThat(generateActualJson(requestType, queryString)).isEqualTo(wrapInSearchReply(expected));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void runSuccessfulTestWithFourDomains(
      RequestType requestType,
      String queryString,
      String domainRoid1,
      String domainRoid2,
      String domainRoid3,
      String domainRoid4,
      String filename) {
    runSuccessfulTestWithFourDomains(
        requestType,
        queryString,
        domainRoid1,
        domainRoid2,
        domainRoid3,
        domainRoid4,
        "none",
        filename);
  }

  private void runSuccessfulTestWithFourDomains(
      RequestType requestType,
      String queryString,
      String domainRoid1,
      String domainRoid2,
      String domainRoid3,
      String domainRoid4,
      String nextQuery,
      String filename) {
    rememberWildcardType(queryString);
    assertThat(generateActualJson(requestType, queryString))
        .isEqualTo(
            jsonFileBuilder()
                .addDomain("domain1.lol", domainRoid1)
                .addDomain("domain2.lol", domainRoid2)
                .addDomain("domain3.lol", domainRoid3)
                .addDomain("domain4.lol", domainRoid4)
                .setNextQuery(nextQuery)
                .load(filename));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void runNotFoundTest(RequestType requestType, String queryString, String errorMessage) {
    rememberWildcardType(queryString);
    assertThat(generateActualJson(requestType, queryString))
        .isEqualTo(generateExpectedJsonError(errorMessage, 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  private void verifyMetrics(SearchType searchType, Optional<Long> numDomainsRetrieved) {
    verifyMetrics(
        searchType, numDomainsRetrieved, Optional.empty(), IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      SearchType searchType,
      Optional<Long> numDomainsRetrieved,
      IncompletenessWarningType incompletenessWarningType) {
    verifyMetrics(searchType, numDomainsRetrieved, Optional.empty(), incompletenessWarningType);
  }

  private void verifyMetrics(
      SearchType searchType, Optional<Long> numDomainsRetrieved, Optional<Long> numHostsRetrieved) {
    verifyMetrics(
        searchType, numDomainsRetrieved, numHostsRetrieved, IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      SearchType searchType, long numDomainsRetrieved, long numHostsRetrieved) {
    verifyMetrics(
        searchType,
        Optional.of(numDomainsRetrieved),
        Optional.of(numHostsRetrieved),
        IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      SearchType searchType,
      Optional<Long> numDomainsRetrieved,
      Optional<Long> numHostsRetrieved,
      IncompletenessWarningType incompletenessWarningType) {
    metricSearchType = searchType;
    verifyMetrics(
        EndpointType.DOMAINS,
        POST,
        action.includeDeletedParam.orElse(false),
        action.registrarParam.isPresent(),
        numDomainsRetrieved,
        numHostsRetrieved,
        Optional.empty(),
        incompletenessWarningType);
  }

  private void verifyErrorMetrics(SearchType searchType) {
    verifyErrorMetrics(searchType, Optional.of(0L), Optional.empty(), 404);
  }

  private void verifyErrorMetrics(
      SearchType searchType, Optional<Long> numDomainsRetrieved, int statusCode) {
    verifyErrorMetrics(searchType, numDomainsRetrieved, Optional.empty(), statusCode);
  }

  private void verifyErrorMetrics(
      SearchType searchType,
      Optional<Long> numDomainsRetrieved,
      Optional<Long> numHostsRetrieved,
      int statusCode) {
    metricStatusCode = statusCode;
    verifyMetrics(searchType, numDomainsRetrieved, numHostsRetrieved);
  }

  /**
   * Checks multi-page result set navigation using the cursor.
   *
   * <p>If there are more results than the max result set size, the RDAP code returns a cursor token
   * which can be used in a subsequent call to get the next chunk of results.
   *
   * @param requestType the type of query (name, nameserver name or nameserver address)
   * @param paramValue the query string
   * @param expectedNames an immutable list of the domain names we expect to retrieve
   */
  private void checkCursorNavigation(
      RequestType requestType, String paramValue, ImmutableList<String> expectedNames)
      throws Exception {
    String cursor = null;
    int expectedNameOffset = 0;
    int expectedPageCount =
        (expectedNames.size() + action.rdapResultSetMaxSize - 1) / action.rdapResultSetMaxSize;
    for (int pageNum = 0; pageNum < expectedPageCount; pageNum++) {
      JsonObject results = generateActualJson(requestType, paramValue, cursor);
      assertThat(response.getStatus()).isEqualTo(200);
      String linkToNext = RdapTestHelper.getLinkToNext(results);
      if (pageNum == expectedPageCount - 1) {
        assertThat(linkToNext).isNull();
      } else {
        assertThat(linkToNext).isNotNull();
        int pos = linkToNext.indexOf("cursor=");
        assertThat(pos).isAtLeast(0);
        cursor = URLDecoder.decode(linkToNext.substring(pos + 7), "UTF-8");
        JsonArray searchResults = results.getAsJsonArray("domainSearchResults");
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
  public void testInvalidPath_rejected() {
    action.requestPath = actionPath + "/path";
    action.run();
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(SearchType.NONE, Optional.empty(), 400);
  }

  @Test
  public void testInvalidRequest_rejected() {
    assertThat(generateActualJson(RequestType.NONE, null))
        .isEqualTo(
            generateExpectedJsonError(
                "You must specify either name=XXXX, nsLdhName=YYYY or nsIp=ZZZZ", 400));
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(SearchType.NONE, Optional.empty(), 400);
  }

  @Test
  public void testInvalidWildcard_rejected() {
    assertThat(generateActualJson(RequestType.NAME, "exam*ple"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of a label, but"
                    + " was: 'exam*ple'",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.empty(), 422);
  }

  @Test
  public void testMultipleWildcards_rejected() {
    assertThat(generateActualJson(RequestType.NAME, "*.*"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of a label, but"
                    + " was: '*.*'",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNoCharactersToMatch_rejected() {
    rememberWildcardType("*");
    assertThat(generateActualJson(RequestType.NAME, "*"))
        .isEqualTo(
            generateExpectedJsonError(
                "Initial search string is required for wildcard domain searches without a TLD"
                    + " suffix",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.empty(), 422);
  }

  @Test
  public void testFewerThanTwoCharactersToMatch_rejected() {
    rememberWildcardType("a*");
    assertThat(generateActualJson(RequestType.NAME, "a*"))
        .isEqualTo(
            generateExpectedJsonError(
                "Initial search string must be at least 2 characters for wildcard domain searches"
                    + " without a TLD suffix",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.empty(), 422);
  }

  @Test
  public void testDomainMatch_found() {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_foundWithUpperCase() {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "CaT.lOl", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_found_sameRegistrarRequested() {
    login("evilregistrar");
    action.registrarParam = Optional.of("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_found_asAdministrator() {
    loginAsAdmin();
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_found_loggedInAsOtherRegistrar() {
    login("otherregistrar");
    runSuccessfulTestWithCatLol(
        RequestType.NAME, "cat.lol", "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  /*
   * This test is flaky because IDN.toASCII may or may not remove the trailing dot of its own
   * accord. If it does, the test will pass.
   */
  @Ignore
  @Test
  public void testDomainMatchWithTrailingDot_notFound() {
    runNotFoundTest(RequestType.NAME, "cat.lol.", "No domains found");
  }

  @Test
  public void testDomainMatch_cat2_lol_found() {
    login("evilregistrar");
    runSuccessfulTestWithCat2Lol(RequestType.NAME, "cat2.lol", "rdap_domain_cat2.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_example_found() {
    login("evilregistrar");
    runSuccessfulTest(
        RequestType.NAME,
        "cat.example",
        jsonFileBuilder()
            .addDomain("cat.example", "21-EXAMPLE")
            .addRegistrar("St. John Chrysostom")
            .addNameserver("ns1.cat.lol", "8-ROID")
            .addNameserver("ns2.external.tld", "1F-ROID")
            .load("rdap_domain_no_contacts_with_remark.json"));
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_idn_unicode_found() {
    runSuccessfulTest(
        RequestType.NAME,
        "cat.みんな",
        jsonFileBuilder()
            .addDomain("cat.みんな", "2D-Q9JYB4C")
            .addRegistrar("みんな")
            .addNameserver("ns1.cat.みんな", "29-ROID")
            .addNameserver("ns2.cat.みんな", "2B-ROID")
            .load("rdap_domain_unicode_no_contacts_with_remark.json"));
    // The unicode gets translated to ASCII before getting parsed into a search pattern.
    metricPrefixLength = 15;
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_idn_punycode_found() {
    runSuccessfulTest(
        RequestType.NAME,
        "cat.xn--q9jyb4c",
        jsonFileBuilder()
            .addDomain("cat.みんな", "2D-Q9JYB4C")
            .addRegistrar("みんな")
            .addNameserver("ns1.cat.みんな", "29-ROID")
            .addNameserver("ns2.cat.みんな", "2B-ROID")
            .load("rdap_domain_unicode_no_contacts_with_remark.json"));
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_1_test_found() {
    runSuccessfulTest(
        RequestType.NAME,
        "cat.1.test",
        jsonFileBuilder()
            .addDomain("cat.1.test", "39-1_TEST")
            .addRegistrar("1.test")
            .addNameserver("ns1.cat.1.test", "35-ROID")
            .addNameserver("ns2.cat.2.test", "37-ROID")
            .load("rdap_domain_no_contacts_with_remark.json"));
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_castar_1_test_found() {
    runSuccessfulTest(
        RequestType.NAME,
        "ca*.1.test",
        jsonFileBuilder()
            .addDomain("cat.1.test", "39-1_TEST")
            .addRegistrar("1.test")
            .addNameserver("ns1.cat.1.test", "35-ROID")
            .addNameserver("ns2.cat.2.test", "37-ROID")
            .load("rdap_domain_no_contacts_with_remark.json"));
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_castar_test_notFound() {
    runNotFoundTest(RequestType.NAME, "ca*.test", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_catstar_lol_found() {
    rememberWildcardType("cat*.lol");
    assertThat(generateActualJson(RequestType.NAME, "cat*.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsCatStarReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L));
  }

  @Test
  public void testDomainMatch_cstar_lol_found() {
    rememberWildcardType("c*.lol");
    assertThat(generateActualJson(RequestType.NAME, "c*.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsCatStarReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L));
  }

  @Test
  public void testDomainMatch_qstar_lol_notFound() {
    runNotFoundTest(RequestType.NAME, "q*.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_star_lol_found() {
    rememberWildcardType("*.lol");
    assertThat(generateActualJson(RequestType.NAME, "*.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsCatStarReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L));
  }

  @Test
  public void testDomainMatch_star_lol_found_sameRegistrarRequested() {
    action.registrarParam = Optional.of("evilregistrar");
    rememberWildcardType("*.lol");
    assertThat(generateActualJson(RequestType.NAME, "*.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsCatStarReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L));
  }

  @Test
  public void testDomainMatch_star_lol_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("otherregistrar");
    rememberWildcardType("*.lol");
    runNotFoundTest(RequestType.NAME, "*.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_cat_star_found() {
    rememberWildcardType("cat.*");
    assertThat(generateActualJson(RequestType.NAME, "cat.*"))
        .isEqualTo(
            jsonFileBuilder()
                .addDomain("cat.1.test", "39-1_TEST")
                .addDomain("cat.example", "21-EXAMPLE")
                .addDomain("cat.lol", "C-LOL")
                .addDomain("cat.みんな", "2D-Q9JYB4C")
                .load("rdap_domains_four_with_one_unicode.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(4L));
  }

  @Test
  public void testDomainMatch_cat_star_foundOne_sameRegistrarRequested() {
    login("evilregistrar");
    action.registrarParam = Optional.of("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.*", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_cat_star_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NAME, "cat.*", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_cat_lstar_found() {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.l*", "rdap_domain.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatch_catstar_found() {
    rememberWildcardType("cat*");
    assertThat(generateActualJson(RequestType.NAME, "cat*"))
        .isEqualTo(
            jsonFileBuilder()
                .addDomain("cat.1.test", "39-1_TEST")
                .addDomain("cat.example", "21-EXAMPLE")
                .addDomain("cat.lol", "C-LOL")
                .addDomain("cat.みんな", "2D-Q9JYB4C")
                .setNextQuery("name=cat*&cursor=Y2F0LnhuLS1xOWp5YjRj")
                .load("rdap_domains_four_with_one_unicode_truncated.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(5L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatchWithWildcardAndEmptySuffix_fails() {
    // Unfortunately, we can't be sure which error is going to be returned. The version of
    // IDN.toASCII used in Eclipse drops a trailing dot, if any. But the version linked in by
    // Blaze throws an error in that situation. So just check that it returns an error.
    rememberWildcardType("exam*..");
    generateActualJson(RequestType.NAME, "exam*..");
    assertThat(response.getStatus()).isIn(Range.closed(400, 499));
  }

  @Test
  public void testDomainMatch_dog_notFound() {
    runNotFoundTest(RequestType.NAME, "dog*", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatchDeletedDomain_notFound() {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatchDeletedDomain_notFound_deletedNotRequested() {
    login("evilregistrar");
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatchDeletedDomain_found_loggedInAsSameRegistrar() {
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatchDeletedDomain_notFound_loggedInAsOtherRegistrar() {
    login("otherregistrar");
    action.includeDeletedParam = Optional.of(true);
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L), 404);
  }

  @Test
  public void testDomainMatchDeletedDomain_found_loggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(RequestType.NAME, "cat.lol", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L));
  }

  @Test
  public void testDomainMatchDeletedDomainWithWildcard_notFound() {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat.lo*", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(1L), 404);
  }

  @Test
  public void testDomainMatchDeletedDomainsWithWildcardAndTld_notFound() {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    persistDomainAsDeleted(domainCatLol2, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NAME, "cat*.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(2L), 404);
  }

  // TODO(b/27378695): reenable or delete this test
  @Ignore
  @Test
  public void testDomainMatchDomainInTestTld_notFound() {
    persistResource(Registry.get("lol").asBuilder().setTldType(Registry.TldType.TEST).build());
    runNotFoundTest(RequestType.NAME, "cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_DOMAIN_NAME);
  }

  @Test
  public void testDomainMatch_manyDeletedDomains_fullResultSet() {
    // There are enough domains to fill a full result set; deleted domains are ignored.
    createManyDomainsAndHosts(4, 4, 2);
    rememberWildcardType("domain*.lol");
    JsonObject obj = generateActualJson(RequestType.NAME, "domain*.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 4);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(16L));
  }

  @Test
  public void testDomainMatch_manyDeletedDomains_partialResultSetDueToInsufficientDomains() {
    // There are not enough domains to fill a full result set.
    createManyDomainsAndHosts(3, 20, 2);
    rememberWildcardType("domain*.lol");
    JsonObject obj = generateActualJson(RequestType.NAME, "domain*.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 3);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(60L));
  }

  @Test
  public void testDomainMatch_manyDeletedDomains_partialResultSetDueToFetchingLimit() {
    // This is not exactly desired behavior, but expected: There are enough domains to fill a full
    // result set, but there are so many deleted domains that we run out of patience before we work
    // our way through all of them.
    createManyDomainsAndHosts(4, 50, 2);
    rememberWildcardType("domain*.lol");
    assertThat(generateActualJson(RequestType.NAME, "domain*.lol"))
        .isEqualTo(
            jsonFileBuilder()
                .addDomain("domain100.lol", "A7-LOL")
                .addDomain("domain150.lol", "75-LOL")
                .addDomain("domain200.lol", "43-LOL")
                .addDomain("domainunused.lol", "unused-LOL")
                .load("rdap_incomplete_domain_result_set.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(
        SearchType.BY_DOMAIN_NAME,
        Optional.of(120L),
        IncompletenessWarningType.MIGHT_BE_INCOMPLETE);
  }

  @Test
  public void testDomainMatch_nontruncatedResultsSet() {
    createManyDomainsAndHosts(4, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NAME,
        "domain*.lol",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "43-LOL",
        "rdap_nontruncated_domains.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(4L));
  }

  @Test
  public void testDomainMatch_truncatedResultsSet() {
    createManyDomainsAndHosts(5, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NAME,
        "domain*.lol",
        "47-LOL",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "name=domain*.lol&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(5L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatch_tldSearchOrderedProperly() {
    createManyDomainsAndHosts(4, 1, 2);
    rememberWildcardType("*.lol");
    assertThat(generateActualJson(RequestType.NAME, "*.lol"))
        .isEqualTo(
            jsonFileBuilder()
                .addDomain("cat.lol", "C-LOL")
                .addDomain("cat2.lol", "17-LOL")
                .addDomain("domain1.lol", "46-LOL")
                .addDomain("domain2.lol", "45-LOL")
                .setNextQuery("name=*.lol&cursor=ZG9tYWluMi5sb2w%3D")
                .load("rdap_domains_four_truncated.json"));
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(5L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatch_reallyTruncatedResultsSet() {
    // Don't use 10 or more domains for this test, because domain10.lol will come before
    // domain2.lol, and you'll get the wrong domains in the result set.
    createManyDomainsAndHosts(9, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NAME,
        "domain*.lol",
        "4B-LOL",
        "4A-LOL",
        "49-LOL",
        "48-LOL",
        "name=domain*.lol&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(5L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatch_truncatedResultsAfterMultipleChunks() {
    createManyDomainsAndHosts(5, 6, 2);
    rememberWildcardType("domain*.lol");
    assertThat(generateActualJson(RequestType.NAME, "domain*.lol"))
        .isEqualTo(
            jsonFileBuilder()
                .addDomain("domain12.lol", "55-LOL")
                .addDomain("domain18.lol", "4F-LOL")
                .addDomain("domain24.lol", "49-LOL")
                .addDomain("domain30.lol", "43-LOL")
                .setNextQuery("name=domain*.lol&cursor=ZG9tYWluMzAubG9s")
                .load("rdap_domains_four_truncated.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_DOMAIN_NAME, Optional.of(27L), IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testDomainMatch_cursorNavigationWithInitialString() throws Exception {
    createManyDomainsAndHosts(11, 1, 2);
    checkCursorNavigation(
        RequestType.NAME,
        "domain*.lol",
        ImmutableList.of(
            "domain1.lol",
            "domain10.lol",
            "domain11.lol",
            "domain2.lol",
            "domain3.lol",
            "domain4.lol",
            "domain5.lol",
            "domain6.lol",
            "domain7.lol",
            "domain8.lol",
            "domain9.lol"));
  }

  @Test
  public void testDomainMatch_cursorNavigationWithTldSuffix() throws Exception {
    createManyDomainsAndHosts(11, 1, 2);
    checkCursorNavigation(
        RequestType.NAME,
        "*.lol",
        ImmutableList.of(
            "cat.lol",
            "cat2.lol",
            "domain1.lol",
            "domain10.lol",
            "domain11.lol",
            "domain2.lol",
            "domain3.lol",
            "domain4.lol",
            "domain5.lol",
            "domain6.lol",
            "domain7.lol",
            "domain8.lol",
            "domain9.lol"));
  }

  @Test
  public void testNameserverMatch_foundMultiple() {
    rememberWildcardType("ns1.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns1.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsNsReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 1);
  }

  @Test
  public void testNameserverMatch_foundMultiple_sameRegistrarRequested() {
    action.registrarParam = Optional.of("TheRegistrar");
    rememberWildcardType("ns1.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns1.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsNsReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 1);
  }

  @Test
  public void testNameserverMatch_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchWithWildcard_found() {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.l*", "rdap_domain.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchWithWildcard_found_sameRegistrarRequested() {
    login("evilregistrar");
    action.registrarParam = Optional.of("TheRegistrar");
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.l*", "rdap_domain.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchWithWildcard_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns2.cat.l*", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchWithWildcardAndDomainSuffix_notFound() {
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns5*.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchWithNoPrefixAndDomainSuffix_found() {
    rememberWildcardType("*.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "*.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsNsReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 2);
  }

  @Test
  public void testNameserverMatchWithOneCharacterPrefixAndDomainSuffix_found() {
    rememberWildcardType("n*.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "n*.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsNsReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 2);
  }

  @Test
  public void
      testNameserverMatchWithOneCharacterPrefixAndDomainSuffix_found_sameRegistrarRequested() {
    action.registrarParam = Optional.of("TheRegistrar");
    rememberWildcardType("n*.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "n*.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsNsReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 2);
  }

  @Test
  public void testNameserverMatchWithPrefixAndDomainSuffix_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NS_LDH_NAME, "n*.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchWithTwoCharacterPrefixAndDomainSuffix_found() {
    rememberWildcardType("ns*.cat.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns*.cat.lol"))
        .isEqualTo(generateExpectedJsonForTwoDomainsNsReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 2, 2);
  }

  @Test
  public void testNameserverMatchWithWildcardAndEmptySuffix_unprocessable() {
    rememberWildcardTypeInvalid();
    generateActualJson(RequestType.NS_LDH_NAME, "ns*.");
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNameserverMatchWithWildcardAndInvalidSuffix_unprocessable() {
    rememberWildcardType("ns*.google.com");
    generateActualJson(RequestType.NS_LDH_NAME, "ns*.google.com");
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNameserverMatch_ns2_cat_lol_found() {
    login("evilregistrar");
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_ns2_dog_lol_found() {
    login("evilregistrar");
    runSuccessfulTestWithCat2Lol(RequestType.NS_LDH_NAME, "ns2.dog.lol", "rdap_domain_cat2.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_ns1_cat_idn_unicode_badRequest() {
    // nsLdhName must use punycode.
    metricWildcardType = WildcardType.INVALID;
    metricPrefixLength = 0;
    generateActualJson(RequestType.NS_LDH_NAME, "ns1.cat.みんな");
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNameserverMatch_ns1_cat_idn_punycode_found() {
    runSuccessfulTest(
        RequestType.NS_LDH_NAME,
        "ns1.cat.xn--q9jyb4c",
        jsonFileBuilder()
            .addDomain("cat.みんな", "2D-Q9JYB4C")
            .addRegistrar("みんな")
            .addNameserver("ns1.cat.みんな", "29-ROID")
            .addNameserver("ns2.cat.みんな", "2B-ROID")
            .load("rdap_domain_unicode_no_contacts_with_remark.json"));
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_ns1_cat_1_test_found() {
    runSuccessfulTest(
        RequestType.NS_LDH_NAME,
        "ns1.cat.1.test",
        jsonFileBuilder()
            .addDomain("cat.1.test", "39-1_TEST")
            .addRegistrar("1.test")
            .addNameserver("ns1.cat.1.test", "35-ROID")
            .addNameserver("ns2.cat.2.test", "37-ROID")
            .load("rdap_domain_no_contacts_with_remark.json"));
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_nsstar_cat_1_test_found() {
    runSuccessfulTest(
        RequestType.NS_LDH_NAME,
        "ns*.cat.1.test",
        jsonFileBuilder()
            .addDomain("cat.1.test", "39-1_TEST")
            .addRegistrar("1.test")
            .addNameserver("ns1.cat.1.test", "35-ROID")
            .addNameserver("ns2.cat.2.test", "37-ROID")
            .load("rdap_domain_no_contacts_with_remark.json"));
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatch_nsstar_test_unprocessable() {
    rememberWildcardType("ns*.1.test");
    generateActualJson(RequestType.NS_LDH_NAME, "ns*.1.test");
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), 422);
  }

  @Test
  public void testNameserverMatchMissing_notFound() {
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.missing.com", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  // TODO(b/27378695): reenable or delete this test
  @Ignore
  @Test
  public void testNameserverMatchDomainsInTestTld_notFound() {
    persistResource(Registry.get("lol").asBuilder().setTldType(Registry.TldType.TEST).build());
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns2.cat.lol", "No matching nameservers found");
  }

  @Test
  public void testNameserverMatchDeletedDomain_notFound() {
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns2.cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testNameserverMatchDeletedDomain_found_loggedInAsSameRegistrar() {
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.lol", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchDeletedDomain_notFound_loggedInAsOtherRegistrar() {
    login("otherregistrar");
    action.includeDeletedParam = Optional.of(true);
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns2.cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testNameserverMatchDeletedDomain_found_loggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns2.cat.lol", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchOneDeletedDomain_foundTheOther() {
    login("evilregistrar");
    persistDomainAsDeleted(domainCatExample, clock.nowUtc().minusDays(1));
    runSuccessfulTestWithCatLol(RequestType.NS_LDH_NAME, "ns1.cat.lol", "rdap_domain.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, 1, 1);
  }

  @Test
  public void testNameserverMatchTwoDeletedDomains_notFound() {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    persistDomainAsDeleted(domainCatExample, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.cat.lol", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testNameserverMatchDeletedNameserver_notFound() {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchDeletedNameserverWithWildcard_notFound() {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1.cat.l*", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchDeletedNameserverWithWildcardAndSuffix_notFound() {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    runNotFoundTest(RequestType.NS_LDH_NAME, "ns1*.cat.lol", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_NAME, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testNameserverMatchManyNameserversForTheSameDomains() {
    // 40 nameservers for each of 3 domains; we should get back all three undeleted domains, because
    // each one references the nameserver.
    createManyDomainsAndHosts(3, 1, 40);
    rememberWildcardType("ns1.domain1.lol");
    JsonObject obj = generateActualJson(RequestType.NS_LDH_NAME, "ns1.domain1.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 3);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(3L), Optional.of(1L));
  }

  @Test
  public void testNameserverMatchManyNameserversForTheSameDomainsWithWildcard() {
    // Same as above, except with a wildcard (that still only finds one nameserver).
    createManyDomainsAndHosts(3, 1, 40);
    rememberWildcardType("ns1.domain1.l*");
    JsonObject obj = generateActualJson(RequestType.NS_LDH_NAME, "ns1.domain1.l*");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 3);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(3L), Optional.of(1L));
  }

  @Test
  public void testNameserverMatchManyNameserversForTheSameDomainsWithSuffix() {
    // Same as above, except that we find all 39 nameservers because of the wildcard. But we
    // should still only return 3 domains, because we merge duplicate domains together in a set.
    // Since we fetch domains by nameserver in batches of 30 nameservers, we need to make sure to
    // have more than that number of nameservers for an effective test.
    createManyDomainsAndHosts(3, 1, 39);
    rememberWildcardType("ns*.domain1.lol");
    JsonObject obj = generateActualJson(RequestType.NS_LDH_NAME, "ns*.domain1.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    checkNumberOfDomainsInResult(obj, 3);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(3L), Optional.of(39L));
  }

  @Test
  public void testNameserverMatch_nontruncatedResultsSet() {
    createManyDomainsAndHosts(4, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_LDH_NAME,
        "ns1.domain1.lol",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "43-LOL",
        "rdap_nontruncated_domains.json");
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(4L), Optional.of(1L));
  }

  @Test
  public void testNameserverMatch_truncatedResultsSet() {
    createManyDomainsAndHosts(5, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_LDH_NAME,
        "ns1.domain1.lol",
        "47-LOL",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "nsLdhName=ns1.domain1.lol&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(
        SearchType.BY_NAMESERVER_NAME,
        Optional.of(5L),
        Optional.of(1L),
        IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testNameserverMatch_reallyTruncatedResultsSet() {
    createManyDomainsAndHosts(9, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_LDH_NAME,
        "ns1.domain1.lol",
        "4B-LOL",
        "4A-LOL",
        "49-LOL",
        "48-LOL",
        "nsLdhName=ns1.domain1.lol&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(
        SearchType.BY_NAMESERVER_NAME,
        Optional.of(9L),
        Optional.of(1L),
        IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testNameserverMatch_duplicatesNotTruncated() {
    // 36 nameservers for each of 4 domains; these should translate into two fetches, which should
    // not trigger the truncation warning because all the domains will be duplicates.
    createManyDomainsAndHosts(4, 1, 36);
    rememberWildcardType("ns*.domain1.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns*.domain1.lol"))
        .isEqualTo(
            jsonFileBuilder()
                .addDomain("domain1.lol", "8A-LOL")
                .addDomain("domain2.lol", "89-LOL")
                .addDomain("domain3.lol", "88-LOL")
                .addDomain("domain4.lol", "87-LOL")
                .load("rdap_nontruncated_domains.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_NAME, Optional.of(4L), Optional.of(36L));
  }

  @Test
  public void testNameserverMatch_incompleteResultsSet() {
    createManyDomainsAndHosts(2, 1, 41);
    rememberWildcardType("ns*.domain1.lol");
    assertThat(generateActualJson(RequestType.NS_LDH_NAME, "ns*.domain1.lol"))
        .isEqualTo(
            jsonFileBuilder()
                .addDomain("domain1.lol", "92-LOL")
                .addDomain("domain2.lol", "91-LOL")
                .load("rdap_incomplete_domains.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(
        SearchType.BY_NAMESERVER_NAME,
        Optional.of(2L),
        Optional.of(41L),
        IncompletenessWarningType.MIGHT_BE_INCOMPLETE);
  }

  @Test
  public void testNameserverMatch_cursorNavigation() throws Exception {
    createManyDomainsAndHosts(8, 1, 2);
    checkCursorNavigation(
        RequestType.NS_LDH_NAME,
        "ns*.domain1.lol",
        ImmutableList.of(
            "domain1.lol",
            "domain2.lol",
            "domain3.lol",
            "domain4.lol",
            "domain5.lol",
            "domain6.lol",
            "domain7.lol",
            "domain8.lol"));
  }

  @Test
  public void testAddressMatchV4Address_invalidAddress() {
    rememberWildcardType("1.2.3.4.5.6.7.8.9");
    generateActualJson(RequestType.NS_IP, "1.2.3.4.5.6.7.8.9");
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.empty(), 400);
  }

  @Test
  public void testAddressMatchV4Address_foundMultiple() {
    rememberWildcardType("1.2.3.4");
    assertThat(generateActualJson(RequestType.NS_IP, "1.2.3.4"))
        .isEqualTo(generateExpectedJsonForTwoDomainsNsReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 2, 1);
  }

  @Test
  public void testAddressMatchV4Address_foundMultiple_sameRegistrarRequested() {
    action.registrarParam = Optional.of("TheRegistrar");
    rememberWildcardType("1.2.3.4");
    assertThat(generateActualJson(RequestType.NS_IP, "1.2.3.4"))
        .isEqualTo(generateExpectedJsonForTwoDomainsNsReply());
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 2, 1);
  }

  @Test
  public void testAddressMatchV4Address_notFound_differentRegistrarRequested() {
    action.registrarParam = Optional.of("otherregistrar");
    runNotFoundTest(RequestType.NS_IP, "1.2.3.4", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testAddressMatchV6Address_foundOne() {
    runSuccessfulTestWithCatLol(
        RequestType.NS_IP,
        "bad:f00d:cafe:0:0:0:15:beef",
        "rdap_domain_no_contacts_with_remark.json");
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 1, 1);
  }

  @Test
  public void testAddressMatchLocalhost_notFound() {
    runNotFoundTest(RequestType.NS_IP, "127.0.0.1", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.empty(), Optional.of(0L), 404);
  }

  // TODO(b/27378695): reenable or delete this test
  @Ignore
  @Test
  public void testAddressMatchDomainsInTestTld_notFound() {
    persistResource(Registry.get("lol").asBuilder().setTldType(Registry.TldType.TEST).build());
    persistResource(Registry.get("example").asBuilder().setTldType(Registry.TldType.TEST).build());
    runNotFoundTest(RequestType.NS_IP, "127.0.0.1", "No matching nameservers found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS);
  }

  @Test
  public void testAddressMatchDeletedDomain_notFound() {
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runNotFoundTest(RequestType.NS_IP, "bad:f00d:cafe:0:0:0:15:beef", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testAddressMatchDeletedDomain_found_loggedInAsSameRegistrar() {
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(
        RequestType.NS_IP, "bad:f00d:cafe:0:0:0:15:beef", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 1, 1);
  }

  @Test
  public void testAddressMatchDeletedDomain_notFound_loggedInAsOtherRegistrar() {
    login("otherregistrar");
    action.includeDeletedParam = Optional.of(true);
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NS_IP, "bad:f00d:cafe:0:0:0:15:beef", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testAddressMatchDeletedDomain_found_loggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    deleteCatLol();
    runSuccessfulTestWithCatLol(
        RequestType.NS_IP, "bad:f00d:cafe:0:0:0:15:beef", "rdap_domain_deleted.json");
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 1, 1);
  }

  @Test
  public void testAddressMatchOneDeletedDomain_foundTheOther() {
    login("evilregistrar");
    persistDomainAsDeleted(domainCatExample, clock.nowUtc().minusDays(1));
    rememberWildcardType("1.2.3.4");
    assertThat(generateActualJson(RequestType.NS_IP, "1.2.3.4"))
        .isEqualTo(
            wrapInSearchReply(
                jsonFileBuilder()
                    .addDomain("cat.lol", "C-LOL")
                    .addRegistrar("Yes Virginia <script>")
                    .addNameserver("ns1.cat.lol", "8-ROID")
                    .addNameserver("ns2.cat.lol", "A-ROID")
                    .addContact("4-ROID")
                    .addContact("6-ROID")
                    .addContact("2-ROID")
                    .load("rdap_domain.json")));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 1, 1);
  }

  @Test
  public void testAddressMatchTwoDeletedDomains_notFound() {
    persistDomainAsDeleted(domainCatLol, clock.nowUtc().minusDays(1));
    persistDomainAsDeleted(domainCatExample, clock.nowUtc().minusDays(1));
    runNotFoundTest(RequestType.NS_IP, "1.2.3.4", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.of(0L), Optional.of(1L), 404);
  }

  @Test
  public void testAddressMatchDeletedNameserver_notFound() {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    runNotFoundTest(RequestType.NS_IP, "1.2.3.4", "No domains found");
    verifyErrorMetrics(SearchType.BY_NAMESERVER_ADDRESS, Optional.empty(), Optional.of(0L), 404);
  }

  @Test
  public void testAddressMatch_nontruncatedResultsSet() {
    createManyDomainsAndHosts(4, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_IP,
        "5.5.5.1",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "43-LOL",
        "rdap_nontruncated_domains.json");
    verifyMetrics(SearchType.BY_NAMESERVER_ADDRESS, 4, 1);
  }

  @Test
  public void testAddressMatch_truncatedResultsSet() {
    createManyDomainsAndHosts(5, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_IP,
        "5.5.5.1",
        "47-LOL",
        "46-LOL",
        "45-LOL",
        "44-LOL",
        "nsIp=5.5.5.1&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(
        SearchType.BY_NAMESERVER_ADDRESS,
        Optional.of(5L),
        Optional.of(1L),
        IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testAddressMatch_reallyTruncatedResultsSet() {
    createManyDomainsAndHosts(9, 1, 2);
    runSuccessfulTestWithFourDomains(
        RequestType.NS_IP,
        "5.5.5.1",
        "4B-LOL",
        "4A-LOL",
        "49-LOL",
        "48-LOL",
        "nsIp=5.5.5.1&cursor=ZG9tYWluNC5sb2w%3D",
        "rdap_domains_four_truncated.json");
    verifyMetrics(
        SearchType.BY_NAMESERVER_ADDRESS,
        Optional.of(9L),
        Optional.of(1L),
        IncompletenessWarningType.TRUNCATED);
  }

  @Test
  public void testAddressMatch_cursorNavigation() throws Exception {
    createManyDomainsAndHosts(7, 1, 2);
    checkCursorNavigation(
        RequestType.NS_IP,
        "5.5.5.1",
        ImmutableList.of(
            "domain1.lol",
            "domain2.lol",
            "domain3.lol",
            "domain4.lol",
            "domain5.lol",
            "domain6.lol",
            "domain7.lol",
            "domain8.lol"));
  }
}
