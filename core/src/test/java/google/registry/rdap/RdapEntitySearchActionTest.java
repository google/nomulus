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
import static google.registry.rdap.RdapTestHelper.parseJsonObject;
import static google.registry.request.Action.Method.GET;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarPocs;
import static google.registry.testing.GsonSubject.assertAboutJson;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.model.ImmutableObject;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.testing.FakeResponse;
import java.net.URLDecoder;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapEntitySearchAction}. */
class RdapEntitySearchActionTest extends RdapSearchActionTestCase<RdapEntitySearchAction> {

  private static final String BINKY_ADDRESS = "\"123 Blinky St\", \"Blinkyland\"";
  private static final String BINKY_FULL_NAME = "Blinky (赤ベイ)";

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
    persistResources(makeRegistrarPocs(registrarDeleted));

    // inactive
    registrarInactive =
        persistResource(makeRegistrar("2-RegistrarInact", "No Way", Registrar.State.PENDING, 21L));
    persistResources(makeRegistrarPocs(registrarInactive));

    // test
    registrarTest =
        persistResource(
            makeRegistrar("2-RegistrarTest", "Da Test Registrar", Registrar.State.ACTIVE)
                .asBuilder()
                .setType(Registrar.Type.TEST)
                .setIanaIdentifier(null)
                .build());
    persistResources(makeRegistrarPocs(registrarTest));

    action.fnParam = Optional.empty();
    action.handleParam = Optional.empty();
  }

  private JsonObject addBoilerplate(JsonObject jsonObject) {
    jsonObject = RdapTestHelper.wrapInSearchReply("entitySearchResults", jsonObject);
    return addPermanentBoilerplateNotices(jsonObject);
  }

  private void createManyRegistrars(int numRegistrars) {
    ImmutableList.Builder<ImmutableObject> resourcesBuilder = new ImmutableList.Builder<>();
    for (int i = 1; i <= numRegistrars; i++) {
      Registrar registrar =
          makeRegistrar(
              String.format("registrar%d", i),
              String.format("Entity %d", i),
              Registrar.State.ACTIVE,
              300L + i);
      resourcesBuilder.add(registrar);
      resourcesBuilder.addAll(makeRegistrarPocs(registrar));
    }
    persistResources(resourcesBuilder.build());
  }

  private void verifyMetrics() {
    verifyMetrics(IncompletenessWarningType.COMPLETE);
  }

  private void verifyMetrics(IncompletenessWarningType incompletenessWarningType) {
    verifyMetrics(
        EndpointType.ENTITIES,
        GET,
        action.includeDeletedParam.orElse(false),
        action.registrarParam.isPresent(),
        Optional.empty(),
        Optional.empty(),
        incompletenessWarningType);
  }

  private void verifyErrorMetrics() {
    verifyErrorMetrics(404);
  }

  private void verifyErrorMetrics(int statusCode) {
    metricStatusCode = statusCode;
    verifyMetrics();
  }

  private void runNotFoundNameTest(String queryString) {
    rememberWildcardType(queryString);
    assertAboutJson()
        .that(generateActualJsonWithFullName(queryString))
        .isEqualTo(generateExpectedJsonError("No entities found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  private void runNotFoundHandleTest(String queryString) {
    rememberWildcardType(queryString);
    assertAboutJson()
        .that(generateActualJsonWithHandle(queryString))
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
    verifyErrorMetrics(400);
  }

  @Test
  void testInvalidRequest_rejected() {
    action.run();
    assertAboutJson()
        .that(parseJsonObject(response.getPayload()))
        .isEqualTo(
            generateExpectedJsonError("You must specify either fn=XXXX or handle=YYYY", 400));
    assertThat(response.getStatus()).isEqualTo(400);
    verifyErrorMetrics(400);
  }

  @Test
  void testNameMatch_suffixRejected() {
    assertAboutJson()
        .that(generateActualJsonWithFullName("exam*ple"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of the query,"
                    + " but was: 'exam*ple'",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(422);
  }

  @Test
  void testHandleMatch_suffixRejected() {
    assertAboutJson()
        .that(generateActualJsonWithHandle("exam*ple"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of the query,"
                    + " but was: 'exam*ple'",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(422);
  }

  @Test
  void testMultipleWildcards_rejected() {
    assertAboutJson()
        .that(generateActualJsonWithHandle("*.*"))
        .isEqualTo(
            generateExpectedJsonError(
                "Query can only have a single wildcard, and it must be at the end of the query,"
                    + " but was: '*.*'",
                422));
    assertThat(response.getStatus()).isEqualTo(422);
    verifyErrorMetrics(422);
  }

  @Test
  void testNameMatchRegistrar_found() {
    login("2-RegistrarTest");
    rememberWildcardType("Yes Virginia <script>");
    assertAboutJson()
        .that(generateActualJsonWithFullName("Yes Virginia <script>"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("20", "Yes Virginia <script>", "active", null)
                    .load("rdap_registrar.json")));
    verifyMetrics();
  }

  @Test
  void testNameMatchRegistrar_found_specifyingSameRegistrar() {
    action.registrarParam = Optional.of("2-Registrar");
    rememberWildcardType("Yes Virginia <script>");
    assertAboutJson()
        .that(generateActualJsonWithFullName("Yes Virginia <script>"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("20", "Yes Virginia <script>", "active", null)
                    .load("rdap_registrar.json")));
    verifyMetrics();
  }

  @Test
  void testNameMatchRegistrar_notFound_specifyingDifferentRegistrar() {
    action.registrarParam = Optional.of("2-RegistrarTest");
    runNotFoundNameTest("Yes Virginia <script>");
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchRegistrars_nonTruncated() {
    createManyRegistrars(4);
    rememberWildcardType("Entity *");
    assertAboutJson()
        .that(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(jsonFileBuilder().load("rdap_nontruncated_registrars.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics();
  }

  @Test
  void testNameMatchRegistrars_truncated() {
    createManyRegistrars(5);
    rememberWildcardType("Entity *");
    assertAboutJson()
        .that(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(
            jsonFileBuilder()
                .put("NAME", "fn=Entity+*&cursor=RW50aXR5IDQ%3D")
                .load("rdap_truncated_registrars.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatchRegistrars_reallyTruncated() {
    createManyRegistrars(9);
    rememberWildcardType("Entity *");
    assertAboutJson()
        .that(generateActualJsonWithFullName("Entity *"))
        .isEqualTo(
            jsonFileBuilder()
                .put("NAME", "fn=Entity+*&cursor=RW50aXR5IDQ%3D")
                .load("rdap_truncated_registrars.json"));
    assertThat(response.getStatus()).isEqualTo(200);
    verifyMetrics(IncompletenessWarningType.TRUNCATED);
  }

  @Test
  void testNameMatchRegistrars_cursorNavigation() throws Exception {
    createManyRegistrars(13);
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
    createManyRegistrars(13);
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
  void testNameMatchRegistrar_notFound_inactive() {
    runNotFoundNameTest("No Way");
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchRegistrar_notFound_inactiveAsDifferentRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-Registrar");
    runNotFoundNameTest("No Way");
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchRegistrar_found_inactiveAsSameRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-RegistrarInact");
    rememberWildcardType("No Way");
    assertAboutJson()
        .that(generateActualJsonWithFullName("No Way"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("21", "No Way", "inactive", null)
                    .load("rdap_registrar.json")));
    verifyMetrics();
  }

  @Test
  void testNameMatchRegistrar_found_inactiveAsAdmin() {
    action.includeDeletedParam = Optional.of(true);
    loginAsAdmin();
    rememberWildcardType("No Way");
    assertAboutJson()
        .that(generateActualJsonWithFullName("No Way"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("21", "No Way", "inactive", null)
                    .load("rdap_registrar.json")));
    verifyMetrics();
  }

  @Test
  void testNameMatchRegistrar_notFound_test() {
    runNotFoundNameTest("Da Test Registrar");
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchRegistrar_notFound_testAsDifferentRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-Registrar");
    runNotFoundNameTest("Da Test Registrar");
    verifyErrorMetrics();
  }

  @Test
  void testNameMatchRegistrar_found_testAsSameRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-RegistrarTest");
    rememberWildcardType("Da Test Registrar");
    assertAboutJson()
        .that(generateActualJsonWithFullName("Da Test Registrar"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("not applicable", "Da Test Registrar", "active", null)
                    .load("rdap_registrar_test.json")));
    verifyMetrics();
  }

  @Test
  void testNameMatchRegistrar_found_testAsAdmin() {
    action.includeDeletedParam = Optional.of(true);
    loginAsAdmin();
    rememberWildcardType("Da Test Registrar");
    assertAboutJson()
        .that(generateActualJsonWithFullName("Da Test Registrar"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("not applicable", "Da Test Registrar", "active", null)
                    .load("rdap_registrar_test.json")));
    verifyMetrics();
  }

  @Test
  void testHandleMatchRegistrar_found() {
    rememberWildcardType("20");
    assertAboutJson()
        .that(generateActualJsonWithHandle("20"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("20", "Yes Virginia <script>", "active", null)
                    .load("rdap_registrar.json")));
    verifyMetrics();
  }

  @Test
  void testHandleMatchRegistrar_found_specifyingSameRegistrar() {
    action.registrarParam = Optional.of("2-Registrar");
    rememberWildcardType("20");
    assertAboutJson()
        .that(generateActualJsonWithHandle("20"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("20", "Yes Virginia <script>", "active", null)
                    .load("rdap_registrar.json")));
    verifyMetrics();
  }

  @Test
  void testHandleMatchRegistrar_notFound_specifyingDifferentRegistrar() {
    action.registrarParam = Optional.of("2-RegistrarTest");
    runNotFoundHandleTest("20");
    verifyErrorMetrics();
  }

  @Test
  void testHandleMatchRegistrar_notFound_wildcard() {
    runNotFoundHandleTest("3test*");
    verifyErrorMetrics();
  }

  @Test
  void testHandleMatchRegistrars_cursorNavigationThroughAll() throws Exception {
    createManyRegistrars(13);
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
  void testHandleMatchRegistrar_notFound_inactive() {
    runNotFoundHandleTest("21");
    verifyErrorMetrics();
  }

  @Test
  void testHandleMatchRegistrar_notFound_inactiveAsDifferentRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-Registrar");
    runNotFoundHandleTest("21");
    verifyErrorMetrics();
  }

  @Test
  void testHandleMatchRegistrar_found_inactiveAsSameRegistrar() {
    action.includeDeletedParam = Optional.of(true);
    login("2-RegistrarInact");
    rememberWildcardType("21");
    assertAboutJson()
        .that(generateActualJsonWithHandle("21"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("21", "No Way", "inactive", null)
                    .load("rdap_registrar.json")));
    verifyMetrics();
  }

  @Test
  void testHandleMatchRegistrar_found_inactiveAsAdmin() {
    action.includeDeletedParam = Optional.of(true);
    loginAsAdmin();
    rememberWildcardType("21");
    assertAboutJson()
        .that(generateActualJsonWithHandle("21"))
        .isEqualTo(
            addBoilerplate(
                jsonFileBuilder()
                    .addFullRegistrar("21", "No Way", "inactive", null)
                    .load("rdap_registrar.json")));
    verifyMetrics();
  }
}
