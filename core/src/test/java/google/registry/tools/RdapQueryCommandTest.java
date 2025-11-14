package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action.GkeService;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link RdapQueryCommand}. */
// Use Strictness.LENIENT to avoid UnnecessaryStubbingException for mocks not always used.
@MockitoSettings(strictness = Strictness.LENIENT)
class RdapQueryCommandTest extends CommandTestCase<RdapQueryCommand> {

  @Mock private ServiceConnection mockDefaultConnection;
  @Mock private ServiceConnection mockPubapiConnection;

  @Config("useCanary")
  private boolean testUseCanary = false;

  @BeforeEach
  void rdapQueryBeforeEach() throws Exception {
    command.setConnection(mockDefaultConnection);
    command.useCanary = testUseCanary;

    // Simulate withService returning the mockPubapiConnection
    when(mockDefaultConnection.withService(eq(GkeService.PUBAPI), eq(testUseCanary))) // CORRECTED TYPO HERE
        .thenReturn(mockPubapiConnection);
  }

  // Helper to mock a successful GET response
  private void mockGetResponse(String path, ImmutableMap<String, String> queryParams, String responseBody) throws IOException {
    when(mockPubapiConnection.sendGetRequest(eq(path), eq(queryParams))).thenReturn(responseBody);
  }

  // Helper to mock an error GET response with JSON body
  private void mockGetError(String path, ImmutableMap<String, String> queryParams, int statusCode, String statusMessage, String errorJson) throws IOException {
    String fullErrorMessage = String.format("Error from https://pubapi.registry.google%s: %d %s%s", path, statusCode, statusMessage, errorJson);
    when(mockPubapiConnection.sendGetRequest(eq(path), eq(queryParams)))
        .thenThrow(new IOException(fullErrorMessage));
  }

  // --- Test Cases ---

  @Test
  void testSuccess_domainLookup() throws Exception {
    String path = "/rdap/domain/example.dev";
    String expectedJson = "{\"ldhName\": \"example.dev\"}";
    mockGetResponse(path, ImmutableMap.of(), expectedJson);

    runCommand("domain", "example.dev");

    assertInStdout("ldhName: example.dev");
    assertInStderr("");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(ImmutableMap.of()));
  }

  @Test
  void testSuccess_domainSearch() throws Exception {
    String path = "/rdap/domains";
    ImmutableMap<String, String> query = ImmutableMap.of("name", "exam*.dev");
    String expectedJson = "{\"domainSearchResults\": [{\"ldhName\": \"example.dev\"}]}";
    mockGetResponse(path, query, expectedJson);

    runCommand("domains", "--params", "name=exam*.dev");

    assertInStdout("ldhName: example.dev");
    assertInStderr("");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(query));
  }

  @Test
  void testFailure_nameserverLookup_notFound() throws Exception {
    String path = "/rdap/nameserver/ns1.notfound.dev";
    String errorJson = "{\"title\":\"Not Found\", \"errorCode\":404, \"description\":\"ns1.notfound.dev not found\"}";
    mockGetError(path, ImmutableMap.of(), 404, "Not Found", errorJson);

    runCommand("nameserver", "ns1.notfound.dev");

    assertInStdout("");
    // Adjusted assertion to match the parsed JSON description
    assertInStderr("RDAP Request Failed (Code 404): Not Found");
    assertInStderr("Description: ns1.notfound.dev not found");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(ImmutableMap.of()));
  }

  @Test
  void testFailure_autnumLookup_notImplemented() throws Exception {
    String path = "/rdap/autnum/12345";
    String errorJson = "{\"title\":\"Not Implemented\", \"errorCode\":501, \"description\":[\"Domain Name Registry information only\"]}";
    mockGetError(path, ImmutableMap.of(), 501, "Not Implemented", errorJson);

    runCommand("autnum", "12345");

    assertInStdout("");
    // Adjusted assertion to match the parsed JSON description
    assertInStderr("RDAP Request Failed (Code 501): Not Implemented");
    assertInStderr("Description: Domain Name Registry information only");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(ImmutableMap.of()));
  }

  @Test
  void testFailure_nameserverSearch_unprocessable() throws Exception {
    String path = "/rdap/nameservers";
    ImmutableMap<String, String> query = ImmutableMap.of("name", "ns*.com");
    String errorJson = "{\"title\":\"Unprocessable Entity\", \"errorCode\":422, \"description\":[\"A suffix after a wildcard in a nameserver lookup must be an in-bailiwick domain\"]}";
    mockGetError(path, query, 422, "Unprocessable Entity", errorJson);

    runCommand("nameservers", "--params", "name=ns*.com");

    assertInStdout("");
    // Adjusted assertion to match the parsed JSON description
    assertInStderr("RDAP Request Failed (Code 422): Unprocessable Entity");
    assertInStderr("Description: A suffix after a wildcard in a nameserver lookup must be an in-bailiwick domain");
    verify(mockPubapiConnection).sendGetRequest(eq(path), eq(query));
  }

  @Test
  void testFailure_invalidParamsFormat() {
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> runCommand("domains", "--params", "name-value"));
    assertThat(thrown).hasMessageThat().contains("Invalid parameter format: name-value");
  }

  @Test
  void testFailure_missingPathSegments() {
    // Expect ParameterException from JCommander for missing required parameters
    ParameterException thrown = assertThrows(ParameterException.class, () -> runCommand());
    assertThat(thrown).hasMessageThat().contains("Main parameters are required");
  }
}
