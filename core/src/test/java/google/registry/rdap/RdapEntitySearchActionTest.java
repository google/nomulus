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
import static google.registry.rdap.RdapTestHelper.parseJsonObject;
import static google.registry.request.Action.Method.GET;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistResources;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistDeletedContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.ContactResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.testing.FakeResponse;
import java.net.URLDecoder;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapEntitySearchAction}. */
class RdapEntitySearchActionTest extends RdapSearchActionTestCase<RdapEntitySearchAction> {

  RdapEntitySearchActionTest() {
    super(RdapEntitySearchAction.class);
  }

  private enum QueryType {
    FULL_NAME,
    HANDLE
  }

  private Registrar registrarDeleted;
  private Registrar registrarInactive;
  private Registrar registrarTest;

  private JsonObject generateActualJsonWithFullName(String fn) {
    return generateActualJsonWithFullName(fn, null);
  }

  private JsonObject generateActualJsonWithFullName(String fn, String cursor) {
    metricSearchType = SearchType.BY_FULL_NAME;
    action.fnParam = Optional.of(fn);
    if (cursor == null) {
      action.parameterMap = ImmutableListMultimap.of("fn", fn);
      action.cursorTokenParam = Optional.empty();
    } else {
      action.parameterMap = ImmutableListMultimap.of("fn", fn, "cursor", cursor);
      action.cursorTokenParam = Optional.of(cursor);
    }
    action.run();
    return parseJsonObject(response.getPayload());
  }

  private JsonObject generateActualJsonWithHandle(String handle) {
    return generateActualJsonWithHandle(handle, null);
  }

  private JsonObject generateActualJsonWithHandle(String handle, String cursor) {
    metricSearchType = SearchType.BY_HANDLE;
    action.handleParam = Optional.of(handle);
    if (cursor == null) {
      action.parameterMap = ImmutableListMultimap.of("handle", handle);
      action.cursorTokenParam = Optional.empty();
    } else {
      action.parameterMap = ImmutableListMultimap.of("handle", handle, "cursor", cursor);
      action.cursorTokenParam = Optional.of(cursor);
    }
    action.run();
    return parseJsonObject(response.getPayload());
  }

  @BeforeEach
  void beforeEach() {
    createTld("tld");

    // deleted
    registrarDeleted =
        persistResource(
            makeRegistrar("2-Registrar", "Yes Virginia <script>", Registrar.State.ACTIVE, 20L));
    persistSimpleResources(makeRegistrarContacts(registrarDeleted));

    // inactive
    registrarInactive =
        persistResource(makeRegistrar("2-RegistrarInact", "No Way", Registrar.State.PENDING, 21L));
    persistSimpleResources(makeRegistrarContacts(registrarInactive));

    // test
    registrarTest =
        persistResource(
            makeRegistrar("2-RegistrarTest", "Da Test Registrar", Registrar.State.ACTIVE)
                .asBuilder()
                .setType(Registrar.Type.TEST)
                .setIanaIdentifier(null)
                .build());
    persistSimpleResources(makeRegistrarContacts(registrarTest));

    makeAndPersistContactResource(
        "blinky",
        "Blinky (赤ベイ)",
        "blinky@b.tld",
        ImmutableList.of("123 Blinky St", "Blinkyland"),
        clock.nowUtc(),
        registrarTest);

    makeAndPersistContactResource(
        "blindly",
        "Blindly",
        "blindly@b.tld",
        ImmutableList.of("123 Blindly St", "Blindlyland"),
        clock.nowUtc(),
        registrarTest);

    makeAndPersistDeletedContactResource(
        "clyde",
        clock.nowUtc().minusYears(1),
        registrarDeleted,
        clock.nowUtc().minusMonths(6));

    action.fnParam = Optional.empty();
    action.handleParam = Optional.empty();
    action.subtypeParam = Optional.empty();
  }

  private JsonObject generateExpectedJson(String expectedOutputFile) {
    return loadJsonFile(expectedOutputFile, "TYPE", "entity");
  }

  private JsonObject generateExpectedJson(
      String handle,
      String expectedOutputFile) {
    return generateExpectedJson(handle, null, "active", null, expectedOutputFile);
  }

  private JsonObject generateExpectedJson(
      String handle,
      @Nullable String fullName,
      String status,
      @Nullable String address,
      String expectedOutputFile) {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    builder.put("NAME", handle);
    if (fullName != null) {
      builder.put("FULLNAME", fullName);
    }
    if (address != null) {
      builder.put("ADDRESS", address);
    }
    builder.put("TYPE", "entity");
    builder.put("STATUS", status);
    return loadJsonFile(expectedOutputFile, builder.build());
  }

  private JsonObject generateExpectedJsonForEntity(
      String handle,
      String fullName,
      String status,
      @Nullable String address,
      String expectedOutputFile) {
    JsonObject obj = generateExpectedJson(handle, fullName, status, address, expectedOutputFile);
    obj = RdapTestHelper.wrapInSearchReply("entitySearchResults", obj);
    RdapTestHelper.addNonDomainBoilerplateNotices(obj, "https://example.tld/rdap/");
    return obj;
  }

  private void createManyContactsAndRegistrars(
      int numContacts, int numRegistrars, Registrar contactRegistrar) {
    ImmutableList.Builder<ImmutableObject> resourcesBuilder = new ImmutableList.Builder<>();
    for (int i = 1; i <= numContacts; i++) {
      // Set the ROIDs to a known value for later use.
      ContactResource contact =
          makeContactResource(
                  String.format("contact%d", i),
                  String.format("Entity %d", i),
                  String.format("contact%d@gmail.com", i),
                  contactRegistrar)
              .asBuilder()
              .setRepoId(String.format("%04d-ROID", i))
              .build();
      resourcesBuilder.add(makeHistoryEntry(
          contact, HistoryEntry.Type.CONTACT_CREATE, null, "created", clock.nowUtc()));
      resourcesBuilder.add(contact);
    }
    persistResources(resourcesBuilder.build());
    for (int i = 1; i <= numRegistrars; i++) {
      Registrar registrar =
          makeRegistrar(
              String.format("registrar%d", i),
              String.format("Entity %d", i + numContacts),
              Registrar.State.ACTIVE,
              300L + i);
      resourcesBuilder.add(registrar);
      resourcesBuilder.addAll(makeRegistrarContacts(registrar));
    }
    persistResources(resourcesBuilder.build());
  }

  private void verifyMetrics(long numContactsRetrieved) {
    verifyMetrics(numContactsRetrieved, IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      long numContactsRetrieved, IncompletenessWarningType incompletenessWarningType) {
    verifyMetrics(Optional.of(numContactsRetrieved), incompletenessWarningType);
  }

  private void verifyMetrics(Optional<Long> numContactsRetrieved) {
    verifyMetrics(numContactsRetrieved, IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(
      Optional<Long> numContactsRetrieved, IncompletenessWarningType incompletenessWarningType) {
    verifyMetrics(
        EndpointType.ENTITIES,
        GET,
        action.includeDeletedParam.orElse(false),
        action.registrarParam.isPresent(),
        Optional.empty(),
        Optional.empty(),
        numContactsRetrieved,
        incompletenessWarningType);
  }

  private void verifyErrorMetrics(long numContactsRetrieved) {
    metricStatusCode = 404;
    verifyMetrics(numContactsRetrieved);
  }

  private void verifyErrorMetrics(Optional<Long> numContactsRetrieved, int statusCode) {
    metricStatusCode = statusCode;
    verifyMetrics(numContactsRetrieved);
  }

  private void runSuccessfulNameTestWithBlinky(String queryString, String fileName) {
    runSuccessfulNameTest(
        queryString,
        "2-ROID",
        "Blinky (赤ベイ)",
        "active",
        "\"123 Blinky St\", \"Blinkyland\"",
        fileName);
  }

  private void runSuccessfulNameTest(
      String queryString,
      String handle,
      @Nullable String fullName,
      String fileName) {
    runSuccessfulNameTest(queryString, handle, fullName, "active", null, fileName);
  }

  private void runSuccessfulNameTest(
      String queryString,
      String handle,
      @Nullable String fullName,
      String status,
      @Nullable String address,
      String fileName) {
    rememberWildcardType(queryString);
    assertThat(generateActualJsonWithFullName(queryString))
        .isEqualTo(generateExpectedJsonForEntity(handle, fullName, status, address, fileName));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void runNotFoundNameTest(String queryString) {
    rememberWildcardType(queryString);
    assertThat(generateActualJsonWithFullName(queryString))
        .isEqualTo(generateExpectedJsonError("No entities found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  private void runSuccessfulHandleTestWithBlinky(String queryString, String fileName) {
    runSuccessfulHandleTest(
        queryString,
        "2-ROID",
        "Blinky (赤ベイ)",
        "active",
        "\"123 Blinky St\", \"Blinkyland\"",
        fileName);
  }

  private void runSuccessfulHandleTest(
      String queryString,
      String handle,
      @Nullable String fullName,
      String fileName) {
    runSuccessfulHandleTest(queryString, handle, fullName, "active", null, fileName);
  }

  private void runSuccessfulHandleTest(
      String queryString,
      String handle,
      @Nullable String fullName,
      String status,
      @Nullable String address,
      String fileName) {
    rememberWildcardType(queryString);
    assertThat(generateActualJsonWithHandle(queryString))
        .isEqualTo(generateExpectedJsonForEntity(handle, fullName, status, address, fileName));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void runNotFoundHandleTest(String queryString) {
    rememberWildcardType(queryString);
    assertThat(generateActualJsonWithHandle(queryString))
        .isEqualTo(generateExpectedJsonError("No entities found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  /**
   * Checks multi-page result set navigation using the cursor.
   *
   * <p>If there are more results than the max result set size, the RDAP code returns a cursor token
   * which can be used in a subsequent call to get the next chunk of results.
   *
   * @param queryType type of query being run
   * @param paramValue the query string
   * @param expectedNames an immutable list of the entity names we expect to retrieve
   */
  private void checkCursorNavigation(
      QueryType queryType, String paramValue, ImmutableList<String> expectedNames)
      throws Exception {
    String cursor = null;
    int expectedNameOffset = 0;
    int expectedPageCount =
        (expectedNames.size() + action.rdapResultSetMaxSize - 1) / action.rdapResultSetMaxSize;
    for (int pageNum = 0; pageNum < expectedPageCount; pageNum++) {
      JsonObject results =
          (queryType == QueryType.FULL_NAME)
              ? generateActualJsonWithFullName(paramValue, cursor)
              : generateActualJsonWithHandle(paramValue, cursor);
      assertThat(response.getStatus()).isEqualTo(200);
      String linkToNext = RdapTestHelper.getLinkToNext(results);
      if (pageNum == expectedPageCount - 1) {
        assertThat(linkToNext).isNull();
      } else {
        assertThat(linkToNext).isNotNull();
        int pos = linkToNext.indexOf("cursor=");
        assertThat(pos).isAtLeast(0);
        cursor = URLDecoder.decode(linkToNext.substring(pos + 7), "UTF-8");
        JsonArray searchResults = results.getAsJsonArray("entitySearchResults");
        assertThat(searchResults).hasSize(action.rdapResultSetMaxSize);
        for (JsonElement item : searchResults) {
          JsonArray vcardArray = item.getAsJsonObject().getAsJsonArray("vcardArray");
          // vcardArray is an array with 2 elements, the first is just a string, the second is an
          // array with all the vcards, starting with a "version" (so we want the second one).
          JsonArray vcardFn = vcardArray.get(1).getAsJsonArray().get(1).getAsJsonArray();
          String name = vcardFn.get(3).getAsString();
          assertThat(name).isEqualTo(expectedNames.get(expectedNameOffset++));
        }
        response = new FakeResponse();
        action.response = response;
      }
    }
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
    assertThat(parseJsonObject(response.getPayload()))
        .isEqualTo(
            generateExpectedJsonError(
                "You must specify either fn=XXXX or handle=YYYY", 400));
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(Optional.empty(), 400);
  }

  @Test
  void testNameMatch_suffixRejected() {
    assertThat(generateActualJsonWithFullName("exam*ple"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of the query,"
                    + " but was: 'exam*ple'",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testHandleMatch_suffixRejected() {
    assertThat(generateActualJsonWithHandle("exam*ple"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of the query,"
                    + " but was: 'exam*ple'",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testMultipleWildcards_rejected() {
    assertThat(generateActualJsonWithHandle("*.*"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of the query,"
                    + " but was: '*.*'",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testNoCharactersToMatch_rejected() {
    rememberWildcardType("*");
    assertThat(generateActualJsonWithHandle("*"))
        .isEqualTo(
            generateExpectedJsonError(
                "Initial search string must be at least 2 characters",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testFewerThanTwoCharactersToMatch_rejected() {
    rememberWildcardType("a*");
    assertThat(generateActualJsonWithHandle("a*"))
        .isEqualTo(
            generateExpectedJsonError(
                "Initial search string must be at least 2 characters",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(Optional.empty(), 422);
  }

  @Test
  void testInvalidSubtype_rejected() {
    action.subtypeParam = Optional.of("Space Aliens");
    assertThat(generateActualJsonWithFullName("Blinky (赤ベイ)"))
        .isEqualTo(
            generateExpectedJsonError(
                "Subtype parameter must specify contacts, registrars or all",
                400));
    assertThat(response.getStatus()).isEqualTo(400);
    metricSearchType = SearchType.NONE; // Error occurs before search type is set.
    verifyErrorMetrics(Optional.empty(), 400);
  }

  @Test
  void testNameMatchContact_found() {
    login("2-RegistrarTest");
    runSuccessfulNameTestWithBlinky("Blinky (赤ベイ)", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testNameMatchContact_found_subtypeAll() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("aLl");
    runSuccessfulNameTestWithBlinky("Blinky (赤ベイ)", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testNameMatchContact_found_subtypeContacts() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("cONTACTS");
    runSuccessfulNameTestWithBlinky("Blinky (赤ベイ)", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testNameMatchContact_notFound_subtypeRegistrars() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("Registrars");
    runNotFoundNameTest("Blinky (赤ベイ)");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContact_found_specifyingSameRegistrar() {
    login("2-RegistrarTest");
    action.registrarParam = Optional.of("2-RegistrarTest");
    runSuccessfulNameTestWithBlinky("Blinky (赤ベイ)", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testNameMatchContact_notFound_specifyingOtherRegistrar() {
    login("2-RegistrarTest");
    action.registrarParam = Optional.of("2-RegistrarInact");
    runNotFoundNameTest("Blinky (赤ベイ)");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContact_found_asAdministrator() {
    loginAsAdmin();
    rememberWildcardType("Blinky (赤ベイ)");
    runSuccessfulNameTestWithBlinky("Blinky (赤ベイ)", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testNameMatchContact_notFound_notLoggedIn() {
    runNotFoundNameTest("Blinky (赤ベイ)");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContact_notFound_loggedInAsOtherRegistrar() {
    login("2-Registrar");
    runNotFoundNameTest("Blinky (赤ベイ)");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContact_found_wildcard() {
    login("2-RegistrarTest");
    runSuccessfulNameTestWithBlinky("Blinky*", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testNameMatchContact_found_wildcardSpecifyingSameRegistrar() {
    login("2-RegistrarTest");
    action.registrarParam = Optional.of("2-RegistrarTest");
    runSuccessfulNameTestWithBlinky("Blinky*", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testNameMatchContact_notFound_wildcardSpecifyingOtherRegistrar() {
    login("2-RegistrarTest");
    action.registrarParam = Optional.of("2-RegistrarInact");
    runNotFoundNameTest("Blinky*");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContact_found_wildcardBoth() {
    login("2-RegistrarTest");
    rememberWildcardType("Blin*");
    assertThat(generateActualJsonWithFullName("Blin*"))
        .isEqualTo(generateExpectedJson("rdap_multiple_contacts2.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(2);
  }

  @Test
  void testNameMatchContact_notFound_deleted() {
    login("2-RegistrarTest");
    runNotFoundNameTest("Cl*");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContact_notFound_deletedWhenLoggedInAsOtherRegistrar() {
    login("2-RegistrarTest");
    action.includeDeletedParam = Optional.of(true);
    runNotFoundNameTest("Cl*");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContact_notFound_deletedWhenLoggedInAsSameRegistrar() {
    login("2-Registrar");
    action.includeDeletedParam = Optional.of(true);
    runNotFoundNameTest("Cl*");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContact_notFound_deletedWhenLoggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    runNotFoundNameTest("Cl*");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_found() {
    login("2-RegistrarTest");
    runSuccessfulNameTest(
        "Yes Virginia <script>", "20", "Yes Virginia <script>", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_found_subtypeAll() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("all");
    runSuccessfulNameTest(
        "Yes Virginia <script>", "20", "Yes Virginia <script>", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_found_subtypeRegistrars() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("REGISTRARS");
    runSuccessfulNameTest(
        "Yes Virginia <script>", "20", "Yes Virginia <script>", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_notFound_subtypeContacts() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("contacts");
    runNotFoundNameTest("Yes Virginia <script>");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_found_specifyingSameRegistrar() {
    action.registrarParam = Optional.of("2-Registrar");
    runSuccessfulNameTest(
        "Yes Virginia <script>", "20", "Yes Virginia <script>", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_notFound_specifyingDifferentRegistrar() {
    action.registrarParam = Optional.of("2-RegistrarTest");
    runNotFoundNameTest("Yes Virginia <script>");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchContacts_nonTruncated() {
    login("2-RegistrarTest");
    createManyContactsAndRegistrars(4, 0, registrarTest);
    rememberWildcardType("Entity *");
    assertThat(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(generateExpectedJson("rdap_nontruncated_contacts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(4);
  }

  @Test
  void testNameMatchContacts_truncated() {
    login("2-RegistrarTest");
    createManyContactsAndRegistrars(5, 0, registrarTest);
    rememberWildcardType("Entity *");
    assertThat(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(
            generateExpectedJson(
                "fn=Entity+*&cursor=YzpFbnRpdHkgNA%3D%3D", "rdap_truncated_contacts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(5, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatchContacts_reallyTruncated() {
    login("2-RegistrarTest");
    createManyContactsAndRegistrars(9, 0, registrarTest);
    rememberWildcardType("Entity *");
    assertThat(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(
            generateExpectedJson(
                "fn=Entity+*&cursor=YzpFbnRpdHkgNA%3D%3D", "rdap_truncated_contacts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    // For contacts, we only need to fetch one result set's worth (plus one).
    verifyMetrics(5, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatchContacts_cursorNavigation() throws Exception {
    login("2-RegistrarTest");
    createManyContactsAndRegistrars(9, 0, registrarTest);
    checkCursorNavigation(
        QueryType.FULL_NAME,
        "Entity *",
        ImmutableList.of(
            "Entity 1",
            "Entity 2",
            "Entity 3",
            "Entity 4",
            "Entity 5",
            "Entity 6",
            "Entity 7",
            "Entity 8",
            "Entity 9"));
  }

  @Test
  void testNameMatchRegistrars_nonTruncated() {
    createManyContactsAndRegistrars(0, 4, registrarTest);
    rememberWildcardType("Entity *");
    assertThat(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(generateExpectedJson("rdap_nontruncated_registrars.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrars_truncated() {
    createManyContactsAndRegistrars(0, 5, registrarTest);
    rememberWildcardType("Entity *");
    assertThat(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(
            generateExpectedJson(
                "fn=Entity+*&cursor=cjpFbnRpdHkgNA%3D%3D", "rdap_truncated_registrars.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(0, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatchRegistrars_reallyTruncated() {
    createManyContactsAndRegistrars(0, 9, registrarTest);
    rememberWildcardType("Entity *");
    assertThat(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(
            generateExpectedJson(
                "fn=Entity+*&cursor=cjpFbnRpdHkgNA%3D%3D", "rdap_truncated_registrars.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(0, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatchRegistrars_cursorNavigation() throws Exception {
    createManyContactsAndRegistrars(0, 13, registrarTest);
    checkCursorNavigation(
        QueryType.FULL_NAME,
        "Entity *",
        ImmutableList.of(
            "Entity 1",
            "Entity 10",
            "Entity 11",
            "Entity 12",
            "Entity 13",
            "Entity 2",
            "Entity 3",
            "Entity 4",
            "Entity 5",
            "Entity 6",
            "Entity 7",
            "Entity 8",
            "Entity 9"));
  }

  @Test
  void testNameMatchRegistrars_cursorNavigationThroughAll() throws Exception {
    createManyContactsAndRegistrars(0, 13, registrarTest);
    action.subtypeParam = Optional.of("registrars");
    checkCursorNavigation(
        QueryType.FULL_NAME,
        "*",
        ImmutableList.of(
            "Entity 1",
            "Entity 10",
            "Entity 11",
            "Entity 12",
            "Entity 13",
            "Entity 2",
            "Entity 3",
            "Entity 4",
            "Entity 5",
            "Entity 6",
            "Entity 7",
            "Entity 8",
            "Entity 9",
            "New Registrar",
            "The Registrar",
            "Yes Virginia <script>"));
  }

  @Test
  void testNameMatchMix_truncated() {
    login("2-RegistrarTest");
    createManyContactsAndRegistrars(3, 3, registrarTest);
    rememberWildcardType("Entity *");
    assertThat(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(
            generateExpectedJson(
                "fn=Entity+*&cursor=cjpFbnRpdHkgNA%3D%3D", "rdap_truncated_mixed_entities.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(3, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatchMix_cursorNavigation() throws Exception {
    login("2-RegistrarTest");
    createManyContactsAndRegistrars(3, 3, registrarTest);
    checkCursorNavigation(
        QueryType.FULL_NAME,
        "Entity *",
        ImmutableList.of(
            "Entity 1",
            "Entity 2",
            "Entity 3",
            "Entity 4",
            "Entity 5",
            "Entity 6"));
  }

  @Test
  void testNameMatchMix_subtypeContacts() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("contacts");
    createManyContactsAndRegistrars(4, 4, registrarTest);
    rememberWildcardType("Entity *");
    assertThat(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(generateExpectedJson("rdap_nontruncated_contacts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(4);
  }

  @Test
  void testNameMatchMix_subtypeRegistrars() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("registrars");
    createManyContactsAndRegistrars(1, 1, registrarTest);
    runSuccessfulNameTest(
        "Entity *", "301", "Entity 2", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_notFound_inactive() {
    runNotFoundNameTest("No Way");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_notFound_inactiveAsDifferentRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-Registrar");
    runNotFoundNameTest("No Way");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_found_inactiveAsSameRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-RegistrarInact");
    runSuccessfulNameTest("No Way", "21", "No Way", "inactive", null, "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_found_inactiveAsAdmin() {
    action.includeDeletedParam = Optional.of(true);
    loginAsAdmin();
    runSuccessfulNameTest("No Way", "21", "No Way", "inactive", null, "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_notFound_test() {
    runNotFoundNameTest("Da Test Registrar");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_notFound_testAsDifferentRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-Registrar");
    runNotFoundNameTest("Da Test Registrar");
    verifyErrorMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_found_testAsSameRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-RegistrarTest");
    runSuccessfulNameTest(
        "Da Test Registrar", "not applicable", "Da Test Registrar", "rdap_registrar_test.json");
    verifyMetrics(0);
  }

  @Test
  void testNameMatchRegistrar_found_testAsAdmin() {
    action.includeDeletedParam = Optional.of(true);
    loginAsAdmin();
    runSuccessfulNameTest(
        "Da Test Registrar", "not applicable", "Da Test Registrar", "rdap_registrar_test.json");
    verifyMetrics(0);
  }

  @Test
  void testHandleMatchContact_found() {
    login("2-RegistrarTest");
    runSuccessfulHandleTestWithBlinky("2-ROID", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_found_subtypeAll() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("all");
    runSuccessfulHandleTestWithBlinky("2-ROID", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_found_subtypeContacts() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("contacts");
    runSuccessfulHandleTestWithBlinky("2-ROID", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_notFound_subtypeRegistrars() {
    login("2-RegistrarTest");
    action.subtypeParam = Optional.of("reGistrars");
    runNotFoundHandleTest("2-ROID");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchContact_found_specifyingSameRegistrar() {
    action.registrarParam = Optional.of("2-RegistrarTest");
    runSuccessfulHandleTestWithBlinky("2-ROID", "rdap_contact_no_personal_data_with_remark.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_notFound_specifyingDifferentRegistrar() {
    action.registrarParam = Optional.of("2-Registrar");
    runNotFoundHandleTest("2-ROID");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchContact_notFound_deleted() {
    login("2-RegistrarTest");
    runNotFoundHandleTest("6-ROID");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchContact_notFound_deletedWhenLoggedInAsOtherRegistrar() {
    login("2-RegistrarTest");
    action.includeDeletedParam = Optional.of(true);
    runNotFoundHandleTest("6-ROID");
    verifyErrorMetrics(1);
  }

  @Test
  void testHandleMatchContact_found_deletedWhenLoggedInAsSameRegistrar() {
    login("2-Registrar");
    action.includeDeletedParam = Optional.of(true);
    runSuccessfulHandleTest(
        "6-ROID",
        "6-ROID",
        "",
        "inactive",
        "",
        "rdap_contact_deleted.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_found_deletedWhenLoggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    runSuccessfulHandleTest(
        "6-ROID",
        "6-ROID",
        "",
        "inactive",
        "",
        "rdap_contact_deleted.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_notFound_deletedWildcard() {
    login("2-RegistrarTest");
    runNotFoundHandleTest("6-ROI*");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchContact_notFound_deletedWildcardWhenLoggedInAsOtherRegistrar() {
    login("2-RegistrarTest");
    action.includeDeletedParam = Optional.of(true);
    runNotFoundHandleTest("6-ROI*");
    verifyErrorMetrics(1);
  }

  @Test
  void testHandleMatchContact_found_deletedWildcardWhenLoggedInAsSameRegistrar() {
    login("2-Registrar");
    action.includeDeletedParam = Optional.of(true);
    runSuccessfulHandleTest(
        "6-ROI*",
        "6-ROID",
        "",
        "inactive",
        "",
        "rdap_contact_deleted.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_found_deletedWildcardWhenLoggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    runSuccessfulHandleTest(
        "6-ROI*",
        "6-ROID",
        "",
        "inactive",
        "",
        "rdap_contact_deleted.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchRegistrar_found() {
    runSuccessfulHandleTest("20", "20", "Yes Virginia <script>", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testHandleMatchRegistrar_found_subtypeAll() {
    action.subtypeParam = Optional.of("all");
    runSuccessfulHandleTest("20", "20", "Yes Virginia <script>", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testHandleMatchRegistrar_found_subtypeRegistrars() {
    action.subtypeParam = Optional.of("registrars");
    runSuccessfulHandleTest("20", "20", "Yes Virginia <script>", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testHandleMatchRegistrar_notFound_subtypeContacts() {
    action.subtypeParam = Optional.of("contacts");
    runNotFoundHandleTest("20");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchRegistrar_found_specifyingSameRegistrar() {
    action.registrarParam = Optional.of("2-Registrar");
    runSuccessfulHandleTest("20", "20", "Yes Virginia <script>", "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testHandleMatchRegistrar_notFound_specifyingDifferentRegistrar() {
    action.registrarParam = Optional.of("2-RegistrarTest");
    runNotFoundHandleTest("20");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchContact_found_wildcardWithResultSetSizeOne() {
    login("2-RegistrarTest");
    action.rdapResultSetMaxSize = 1;
    runSuccessfulHandleTestWithBlinky("2-R*", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_found_wildcard() {
    login("2-RegistrarTest");
    runSuccessfulHandleTestWithBlinky("2-RO*", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_found_wildcardSpecifyingSameRegistrar() {
    action.registrarParam = Optional.of("2-RegistrarTest");
    login("2-RegistrarTest");
    runSuccessfulHandleTestWithBlinky("2-RO*", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_notFound_wildcardSpecifyingDifferentRegistrar() {
    action.registrarParam = Optional.of("2-Registrar");
    login("2-RegistrarTest");
    runNotFoundHandleTest("2-RO*");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchContact_found_deleted() {
    login("2-RegistrarTest");
    runSuccessfulHandleTestWithBlinky("2-RO*", "rdap_contact.json");
    verifyMetrics(1);
  }

  @Test
  void testHandleMatchContact_notFound_wildcard() {
    runNotFoundHandleTest("20*");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchContact_cursorNavigationWithFullLastPage() throws Exception {
    login("2-RegistrarTest");
    createManyContactsAndRegistrars(12, 0, registrarTest);
    checkCursorNavigation(
        QueryType.HANDLE,
        "00*",
        // Contacts are returned in ROID order, not name order, by handle searches.
        ImmutableList.of(
            "Entity 1",
            "Entity 2",
            "Entity 3",
            "Entity 4",
            "Entity 5",
            "Entity 6",
            "Entity 7",
            "Entity 8",
            "Entity 9",
            "Entity 10",
            "Entity 11",
            "Entity 12"));
  }

  @Test
  void testHandleMatchContact_cursorNavigationWithPartialLastPage() throws Exception {
    login("2-RegistrarTest");
    createManyContactsAndRegistrars(13, 0, registrarTest);
    checkCursorNavigation(
        QueryType.HANDLE,
        "00*",
        // Contacts are returned in ROID order, not name order, by handle searches.
        ImmutableList.of(
            "Entity 1",
            "Entity 2",
            "Entity 3",
            "Entity 4",
            "Entity 5",
            "Entity 6",
            "Entity 7",
            "Entity 8",
            "Entity 9",
            "Entity 10",
            "Entity 11",
            "Entity 12",
            "Entity 13"));
  }

  @Test
  void testHandleMatchRegistrar_notFound_wildcard() {
    runNotFoundHandleTest("3test*");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchRegistrars_cursorNavigationThroughAll() throws Exception {
    createManyContactsAndRegistrars(0, 13, registrarTest);
    action.subtypeParam = Optional.of("registrars");
    checkCursorNavigation(
        QueryType.HANDLE,
        "*",
        ImmutableList.of(
            "Entity 1",
            "Entity 10",
            "Entity 11",
            "Entity 12",
            "Entity 13",
            "Entity 2",
            "Entity 3",
            "Entity 4",
            "Entity 5",
            "Entity 6",
            "Entity 7",
            "Entity 8",
            "Entity 9",
            "New Registrar",
            "The Registrar",
            "Yes Virginia <script>"));
  }

  @Test
  void testHandleMatchMix_found_truncated() {
    createManyContactsAndRegistrars(30, 0, registrarTest);
    rememberWildcardType("00*");
    JsonObject obj = generateActualJsonWithHandle("00*");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(obj.getAsJsonArray("entitySearchResults")).hasSize(4);
    verifyMetrics(5, IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testHandleMatchRegistrar_notFound_inactive() {
    runNotFoundHandleTest("21");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchRegistrar_notFound_inactiveAsDifferentRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-Registrar");
    runNotFoundHandleTest("21");
    verifyErrorMetrics(0);
  }

  @Test
  void testHandleMatchRegistrar_found_inactiveAsSameRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-RegistrarInact");
    runSuccessfulHandleTest("21", "21", "No Way", "inactive", null, "rdap_registrar.json");
    verifyMetrics(0);
  }

  @Test
  void testHandleMatchRegistrar_found_inactiveAsAdmin() {
    action.includeDeletedParam = Optional.of(true);
    loginAsAdmin();
    runSuccessfulHandleTest("21", "21", "No Way", "inactive", null, "rdap_registrar.json");
    verifyMetrics(0);
  }
}
