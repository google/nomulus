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

import static com.google.common.net.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.rdap.RdapTestHelper.loadJsonFile;
import static google.registry.rdap.RdapTestHelper.parseJsonObject;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;
import static google.registry.testing.DatastoreHelper.createTld;
import static org.mockito.Mockito.verify;

import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapObjectClasses.BoilerplateType;
import google.registry.rdap.RdapObjectClasses.ReplyPayloadBase;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapActionBase}. */
class RdapActionBaseTest extends RdapActionBaseTestCase<RdapActionBaseTest.RdapTestAction> {

  RdapActionBaseTest() {
    super(RdapTestAction.class);
  }

  /** Dummy RdapActionBase subclass used for testing. */
  @Action(
      service = Action.Service.PUBAPI,
      path = "/rdap/test/",
      method = {GET, HEAD},
      auth = Auth.AUTH_PUBLIC_ANONYMOUS)
  public static class RdapTestAction extends RdapActionBase {

    public RdapTestAction() {
      super("human-readable string", EndpointType.HELP);
    }

    @Override
    public ReplyPayloadBase getJsonObjectForResource(
        String pathSearchString, boolean isHeadRequest) {
      if (pathSearchString.equals("IllegalArgumentException")) {
        throw new IllegalArgumentException();
      }
      if (pathSearchString.equals("RuntimeException")) {
        throw new RuntimeException();
      }
      return new ReplyPayloadBase(BoilerplateType.OTHER) {
        @JsonableElement public String key = "value";
      };
    }
  }

  @BeforeEach
  void beforeEach() {
    createTld("thing");
  }

  @Test
  void testIllegalValue_showsReadableTypeName() {
    assertThat(generateActualJson("IllegalArgumentException")).isEqualTo(generateExpectedJsonError(
        "Not a valid human-readable string",
        400));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testRuntimeException_returns500Error() {
    assertThat(generateActualJson("RuntimeException"))
        .isEqualTo(generateExpectedJsonError("An error was encountered", 500));
    assertThat(response.getStatus()).isEqualTo(500);
  }

  @Test
  void testValidName_works() {
    assertThat(generateActualJson("no.thing")).isEqualTo(loadJsonFile("rdapjson_toplevel.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testContentType_rdapjson_utf8() {
    generateActualJson("no.thing");
    assertThat(response.getContentType().toString())
        .isEqualTo("application/rdap+json; charset=utf-8");
  }

  @Test
  void testHeadRequest_returnsNoContent() {
    assertThat(generateHeadPayload("no.thing")).isEmpty();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testHeadRequestIllegalValue_returnsNoContent() {
    assertThat(generateHeadPayload("IllegalArgumentException")).isEmpty();
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testRdapServer_allowsAllCrossOriginRequests() {
    generateActualJson("no.thing");
    assertThat(response.getHeaders().get(ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("*");
  }

  @Test
  void testMetrics_onSuccess() {
    generateActualJson("no.thing");
    verify(rdapMetrics)
        .updateMetrics(
            RdapMetrics.RdapMetricInformation.builder()
                .setEndpointType(EndpointType.HELP)
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

  @Test
  void testMetrics_onError() {
    generateActualJson("IllegalArgumentException");
    verify(rdapMetrics)
        .updateMetrics(
            RdapMetrics.RdapMetricInformation.builder()
                .setEndpointType(EndpointType.HELP)
                .setSearchType(SearchType.NONE)
                .setWildcardType(WildcardType.INVALID)
                .setPrefixLength(0)
                .setIncludeDeleted(false)
                .setRegistrarSpecified(false)
                .setRole(RdapAuthorization.Role.PUBLIC)
                .setRequestMethod(Action.Method.GET)
                .setStatusCode(400)
                .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE)
                .build());
  }

  @Test
  void testUnformatted() {
    action.requestPath = actionPath + "no.thing";
    action.requestMethod = GET;
    action.run();
    String payload = response.getPayload();
    assertThat(payload).doesNotContain("\n");
    assertThat(parseJsonObject(payload)).isEqualTo(loadJsonFile("rdapjson_toplevel.json"));
  }

  @Test
  void testFormatted() {
    action.requestPath = actionPath + "no.thing?formatOutput=true";
    action.requestMethod = GET;
    action.formatOutputParam = Optional.of(true);
    action.run();
    String payload = response.getPayload();
    assertThat(payload).contains("\n");
    assertThat(parseJsonObject(payload)).isEqualTo(loadJsonFile("rdapjson_toplevel.json"));
  }
}
