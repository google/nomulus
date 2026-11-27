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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.rdap.RdapTestHelper.loadJsonFile;
import static google.registry.rdap.RdapTestHelper.parseJsonObject;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;
import static google.registry.testing.DatabaseHelper.createTld;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.TestLogHandler;
import google.registry.rdap.RdapDomainAction.DomainBlockedByBsaException;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapObjectClasses.BoilerplateType;
import google.registry.rdap.RdapObjectClasses.ReplyPayloadBase;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.HttpException.NotImplementedException;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.auth.Auth;
import google.registry.testing.FakeClock;
import google.registry.util.JdkLoggerConfig;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapActionBase}. */
class RdapActionBaseTest extends RdapActionBaseTestCase<RdapActionBaseTest.RdapTestAction> {

  private final TestLogHandler logHandler = new TestLogHandler();

  RdapActionBaseTest() {
    super(RdapTestAction.class);
  }

  /** Dummy RdapActionBase subclass used for testing. */
  @Action(
      service = Action.Service.PUBAPI,
      path = "/rdap/test/",
      method = {GET, HEAD},
      auth = Auth.AUTH_PUBLIC)
  public static class RdapTestAction extends RdapActionBase {

    public RdapTestAction() {
      super("human-readable string", EndpointType.HELP);
    }

    @Override
    public ReplyPayloadBase getJsonObjectForResource(
        String pathSearchString, boolean isHeadRequest) {
      return switch (pathSearchString) {
        case "IllegalArgumentException" -> throw new IllegalArgumentException();
        case "BadRequestException" -> throw new BadRequestException("bad request");
        case "NotFoundException" -> throw new NotFoundException("not found");
        case "NotImplementedException" -> throw new NotImplementedException("not implemented");
        case "InternalServerErrorException" ->
            throw new InternalServerErrorException("internal error");
        case "ServiceUnavailableException" ->
            throw new ServiceUnavailableException("service unavailable");
        case "DomainBlockedByBsaException" ->
            throw new DomainBlockedByBsaException("blocked.thing blocked by BSA");
        case "RuntimeException" -> throw new RuntimeException();
        case "advanceClock" -> {
          ((FakeClock) clock).advanceBy(Duration.ofMillis(50));
          yield new ReplyPayloadBase(BoilerplateType.OTHER) {
            @JsonableElement String key = "value";
          };
        }
        default ->
            new ReplyPayloadBase(BoilerplateType.OTHER) {
              @JsonableElement String key = "value";
            };
      };
    }
  }

  @BeforeEach
  void beforeEach() {
    createTld("thing");
    JdkLoggerConfig.getConfig(RdapActionBase.class).setLevel(Level.FINE);
    JdkLoggerConfig.getConfig(RdapActionBase.class).addHandler(logHandler);
  }

  @AfterEach
  void afterEach() {
    JdkLoggerConfig.getConfig(RdapActionBase.class).removeHandler(logHandler);
    JdkLoggerConfig.getConfig(RdapActionBase.class).setLevel(null);
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
                .setProcessingTime(0L)
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
                .setProcessingTime(0L)
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

  @Test
  void testMetrics_recordsProcessingTime() {
    generateActualJson("advanceClock");
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
                .setProcessingTime(50L)
                .build());
  }

  @Test
  void testLogging_success_logsPathSearchStringAtFineOnly() {
    generateActualJson("no.thing");

    assertNoInfoOrHigherLogs();
    assertLogAtLevel(Level.FINE, "path search string: 'no.thing'.", null);
  }

  @Test
  void testLogging_domainBlockedByBsa_logsAtFineOnly() {
    generateActualJson("DomainBlockedByBsaException");

    assertThat(response.getStatus()).isEqualTo(404);
    assertNoInfoOrHigherLogs();
    assertLogAtLevel(Level.FINE, "Domain blocked by BSA", DomainBlockedByBsaException.class);
  }

  @Test
  void testLogging_notFoundException_logsAtFineOnly() {
    generateActualJson("NotFoundException");

    assertThat(response.getStatus()).isEqualTo(404);
    assertNoInfoOrHigherLogs();
    assertLogAtLevel(Level.FINE, "Error in RDAP.", NotFoundException.class);
  }

  @Test
  void testLogging_badRequestException_logsAtFineOnly() {
    generateActualJson("BadRequestException");

    assertThat(response.getStatus()).isEqualTo(400);
    assertNoInfoOrHigherLogs();
    assertLogAtLevel(Level.FINE, "Error in RDAP.", BadRequestException.class);
  }

  @Test
  void testLogging_illegalArgumentException_logsAtFineOnly() {
    generateActualJson("IllegalArgumentException");

    assertThat(response.getStatus()).isEqualTo(400);
    assertNoInfoOrHigherLogs();
    assertLogAtLevel(Level.FINE, "Bad request in RDAP.", IllegalArgumentException.class);
  }

  @Test
  void testLogging_notImplementedException_logsAtFineOnly() {
    generateActualJson("NotImplementedException");

    assertThat(response.getStatus()).isEqualTo(501);
    assertNoInfoOrHigherLogs();
    assertLogAtLevel(Level.FINE, "Error in RDAP.", NotImplementedException.class);
  }

  @Test
  void testLogging_internalServerErrorException_logsAtSevere() {
    generateActualJson("InternalServerErrorException");

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(recordsAtLevel(Level.INFO)).isEmpty();
    assertLogAtLevel(Level.SEVERE, "Error in RDAP.", InternalServerErrorException.class);
  }

  @Test
  void testLogging_serviceUnavailableException_logsAtSevere() {
    generateActualJson("ServiceUnavailableException");

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(recordsAtLevel(Level.INFO)).isEmpty();
    assertLogAtLevel(Level.SEVERE, "Error in RDAP.", ServiceUnavailableException.class);
  }

  @Test
  void testLogging_runtimeException_logsAtSevere() {
    generateActualJson("RuntimeException");

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(recordsAtLevel(Level.INFO)).isEmpty();
    assertLogAtLevel(
        Level.SEVERE,
        "Exception encountered while processing RDAP command.",
        RuntimeException.class);
  }

  private void assertNoInfoOrHigherLogs() {
    ImmutableList<LogRecord> infoOrHigher =
        logHandler.getStoredLogRecords().stream()
            .filter(record -> record.getLevel().intValue() >= Level.INFO.intValue())
            .collect(toImmutableList());
    assertThat(infoOrHigher).isEmpty();
  }

  private ImmutableList<LogRecord> recordsAtLevel(Level level) {
    return logHandler.getStoredLogRecords().stream()
        .filter(record -> record.getLevel().equals(level))
        .collect(toImmutableList());
  }

  private void assertLogAtLevel(
      Level expectedLevel,
      String expectedMessage,
      @Nullable Class<? extends Throwable> expectedCause) {
    ImmutableList<LogRecord> matching =
        recordsAtLevel(expectedLevel).stream()
            .filter(record -> expectedMessage.equals(record.getMessage()))
            .collect(toImmutableList());
    assertThat(matching).hasSize(1);
    if (expectedCause == null) {
      assertThat(matching.getFirst().getThrown()).isNull();
    } else {
      assertThat(matching.getFirst().getThrown()).isInstanceOf(expectedCause);
    }
  }
}
