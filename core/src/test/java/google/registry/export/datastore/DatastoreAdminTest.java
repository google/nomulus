// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export.datastore;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.common.collect.ImmutableList;
import google.registry.testing.TestDataHelper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link DatastoreAdmin}. */
@RunWith(JUnit4.class)
public class DatastoreAdminTest {

  private static final String AUTH_HEADER_PREFIX = "Bearer ";
  private static final String ACCESS_TOKEN = "MyAccessToken";
  private static final ImmutableList<String> KINDS =
      ImmutableList.of("Registry", "Registrar", "DomainBase");

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private HttpTransport httpTransport;
  private GoogleCredential googleCredential;
  private DatastoreAdmin datastoreAdmin;

  @Before
  public void setup() {
    httpTransport = new NetHttpTransport();
    googleCredential =
        new GoogleCredential.Builder()
            .setTransport(httpTransport)
            .setJsonFactory(JacksonFactory.getDefaultInstance())
            .setClock(() -> 0)
            .build();
    googleCredential.setAccessToken(ACCESS_TOKEN);
    googleCredential.setExpiresInSeconds(1_000L);

    datastoreAdmin =
        new DatastoreAdmin.Builder(
                googleCredential.getTransport(),
                googleCredential.getJsonFactory(),
                googleCredential)
            .setApplicationName("MyApplication")
            .setProjectId("MyCloudProject")
            .build();
  }

  @Test
  public void testExport() throws IOException {
    DatastoreAdmin.Export export = datastoreAdmin.export("gs://mybucket/path", KINDS);
    HttpRequest httpRequest = export.buildHttpRequest();
    assertThat(httpRequest.getUrl().toString())
        .isEqualTo("https://datastore.googleapis.com/v1/projects/MyCloudProject:export");
    assertThat(httpRequest.getRequestMethod()).isEqualTo("POST");

    assertThat(getRequestContent(httpRequest))
        .hasValue(
            TestDataHelper.loadFile(getClass(), "export_request_content.json")
                .replaceAll("[\\s\\n]+", ""));

    simulateSendRequest(httpRequest);
    assertThat(getAccessToken(httpRequest)).hasValue(ACCESS_TOKEN);
  }

  @Test
  public void testGetOperation() throws IOException {
    DatastoreAdmin.Get get =
        datastoreAdmin.get("projects/MyCloudProject/operations/ASAzNjMwOTEyNjUJ");
    HttpRequest httpRequest = get.buildHttpRequest();
    assertThat(httpRequest.getUrl().toString())
        .isEqualTo(
            "https://datastore.googleapis.com/v1/projects/MyCloudProject/operations/ASAzNjMwOTEyNjUJ");
    assertThat(httpRequest.getRequestMethod()).isEqualTo("GET");
    assertThat(httpRequest.getContent()).isNull();

    simulateSendRequest(httpRequest);
    assertThat(getAccessToken(httpRequest)).hasValue(ACCESS_TOKEN);
  }

  @Test
  public void testListOperations_all() throws IOException {
    DatastoreAdmin.ListOperations listOperations = datastoreAdmin.listAll();
    HttpRequest httpRequest = listOperations.buildHttpRequest();
    assertThat(httpRequest.getUrl().toString())
        .isEqualTo("https://datastore.googleapis.com/v1/projects/MyCloudProject/operations");
    assertThat(httpRequest.getRequestMethod()).isEqualTo("GET");
    assertThat(httpRequest.getContent()).isNull();

    simulateSendRequest(httpRequest);
    assertThat(getAccessToken(httpRequest)).hasValue(ACCESS_TOKEN);
  }

  @Test
  public void testListOperations_filterByStartTime() throws IOException {
    DatastoreAdmin.ListOperations listOperations =
        datastoreAdmin.list("metadata.common.startTime>\"2018-10-31T00:00:00.0Z\"");
    HttpRequest httpRequest = listOperations.buildHttpRequest();
    assertThat(httpRequest.getUrl().toString())
        .isEqualTo(
            "https://datastore.googleapis.com/v1/projects/MyCloudProject/operations"
                + "?filter=metadata.common.startTime%3E%222018-10-31T00:00:00.0Z%22");
    assertThat(httpRequest.getRequestMethod()).isEqualTo("GET");
    assertThat(httpRequest.getContent()).isNull();

    simulateSendRequest(httpRequest);
    assertThat(getAccessToken(httpRequest)).hasValue(ACCESS_TOKEN);
  }

  @Test
  public void testListOperations_filterByState() throws IOException {
    // TODO(weiminyu): consider adding a method to DatastoreAdmin to support query by state.
    DatastoreAdmin.ListOperations listOperations =
        datastoreAdmin.list("metadata.common.state=PROCESSING");
    HttpRequest httpRequest = listOperations.buildHttpRequest();
    assertThat(httpRequest.getUrl().toString())
        .isEqualTo(
            "https://datastore.googleapis.com/v1/projects/MyCloudProject/operations"
                + "?filter=metadata.common.state%3DPROCESSING");
    assertThat(httpRequest.getRequestMethod()).isEqualTo("GET");
    assertThat(httpRequest.getContent()).isNull();

    simulateSendRequest(httpRequest);
    assertThat(getAccessToken(httpRequest)).hasValue(ACCESS_TOKEN);
  }

  private static HttpRequest simulateSendRequest(HttpRequest httpRequest) {
    try {
      httpRequest.setUrl(new GenericUrl("https://localhost:65537")).execute();
    } catch (Exception expected) {
    }
    return httpRequest;
  }

  private static Optional<String> getAccessToken(HttpRequest httpRequest) {
    return httpRequest.getHeaders().getAuthorizationAsList().stream()
        .filter(header -> header.startsWith(AUTH_HEADER_PREFIX))
        .map(header -> header.substring(AUTH_HEADER_PREFIX.length()))
        .findAny();
  }

  private static Optional<String> getRequestContent(HttpRequest httpRequest) throws IOException {
    if (httpRequest.getContent() == null) {
      return Optional.empty();
    }
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    httpRequest.getContent().writeTo(outputStream);
    outputStream.close();
    return Optional.of(outputStream.toString(StandardCharsets.UTF_8.name()));
  }
}
