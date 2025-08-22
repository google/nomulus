// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.dns.writer.powerdns.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.flogger.FluentLogger;
import google.registry.dns.writer.powerdns.client.model.Cryptokey;
import google.registry.dns.writer.powerdns.client.model.Metadata;
import google.registry.dns.writer.powerdns.client.model.Server;
import google.registry.dns.writer.powerdns.client.model.TSIGKey;
import google.registry.dns.writer.powerdns.client.model.Zone;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/**
 * A client for the PowerDNS API.
 *
 * <p>This class is used to interact with the PowerDNS API. It provides methods to list servers, get
 * a server, list zones, get a zone, create a zone, delete a zone, and patch a zone. Based on the <a
 * href="https://raw.githubusercontent.com/PowerDNS/pdns/master/docs/http-api/swagger/authoritative-api-swagger.yaml">PowerDNS
 * API spec</a>.
 *
 * <p>The server ID is retrieved from the server list and is used to make all subsequent requests.
 *
 * <p>The API key is retrieved from the environment variable {@code POWERDNS_API_KEY}.
 */
public class PowerDNSClient {
  // class variables
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String apiKey;

  // static fields
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static String serverId;

  public PowerDNSClient(String baseUrl, String apiKey) {
    // initialize the base URL and API key. The base URL should be of the form
    // https://<server-host-name>/api/v1. An example of a valid API call to the
    // localhost to list servers is http://localhost:8081/api/v1/servers
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;

    // initialize the base URL, API key, and HTTP client
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build();
    this.objectMapper = new ObjectMapper();

    // initialize the Server ID
    initializeServerId();
  }

  private synchronized void initializeServerId() {
    if (serverId == null) {
      try {
        // list the servers and throw an exception if no servers are found
        List<Server> servers = listServers();
        if (servers.isEmpty()) {
          throw new IOException("No servers found");
        }

        // set the server ID to the first server in the list
        serverId = servers.get(0).getId();
      } catch (Exception e) {
        logger.atWarning().withCause(e).log("Failed to get PowerDNS server ID");
      }
    }
  }

  private String bodyToString(final RequestBody requestBody) throws IOException {
    try (Buffer buffer = new Buffer()) {
      if (requestBody != null) {
        requestBody.writeTo(buffer);
      } else {
        return "";
      }
      return buffer.readUtf8();
    }
  }

  private Response logAndExecuteRequest(Request request) throws IOException {
    // log the request and create timestamp for the start time
    logger.atInfo().log(
        "Executing PowerDNS request: %s, url: %s, body: %s",
        request.method(),
        request.url(),
        request.body() != null ? bodyToString(request.body()) : null);
    long startTime = System.currentTimeMillis();

    // validate the server ID is initialized
    if (request.url().toString().contains("/servers/null")) {
      throw new IOException("Server ID is not initialized");
    }

    // execute the request and log the response
    Response response = httpClient.newCall(request).execute();
    logger.atInfo().log("PowerDNS response: %s", response);

    // log the response time and response code
    long endTime = System.currentTimeMillis();
    logger.atInfo().log(
        "Completed PowerDNS request in %d ms, success: %s, response code: %d",
        endTime - startTime, response.isSuccessful(), response.code());

    // return the response
    return response;
  }

  /** ZONE AND SERVER MANAGEMENT */
  public List<Server> listServers() throws IOException {
    Request request =
        new Request.Builder().url(baseUrl + "/servers").header("X-API-Key", apiKey).get().build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to list servers: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(),
          objectMapper.getTypeFactory().constructCollectionType(List.class, Server.class));
    }
  }

  public Server getServer() throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId)
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to get server: " + response);
      }
      return objectMapper.readValue(Objects.requireNonNull(response.body()).string(), Server.class);
    }
  }

  public List<Zone> listZones() throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones")
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to list zones: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(),
          objectMapper.getTypeFactory().constructCollectionType(List.class, Zone.class));
    }
  }

  public Zone getZone(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId)
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to get zone: " + response);
      }
      return objectMapper.readValue(Objects.requireNonNull(response.body()).string(), Zone.class);
    }
  }

  public Zone createZone(Zone zone) throws IOException {
    String json = objectMapper.writeValueAsString(zone);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones")
            .header("X-API-Key", apiKey)
            .post(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to create zone: " + response);
      }
      return objectMapper.readValue(Objects.requireNonNull(response.body()).string(), Zone.class);
    }
  }

  public void deleteZone(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId)
            .header("X-API-Key", apiKey)
            .delete()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to delete zone: " + response);
      }
    }
  }

  public void patchZone(Zone zone) throws IOException {
    String json = objectMapper.writeValueAsString(zone);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zone.getId())
            .header("X-API-Key", apiKey)
            .patch(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to patch zone: " + response);
      }
    }
  }

  public void putZone(Zone zone) throws IOException {
    String json = objectMapper.writeValueAsString(zone);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zone.getId())
            .header("X-API-Key", apiKey)
            .put(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to patch zone: " + response);
      }
    }
  }

  public void notifyZone(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/notify")
            .header("X-API-Key", apiKey)
            .put(RequestBody.create("", MediaType.parse("application/json")))
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to notify zone: " + response);
      }
    }
  }

  public void rectifyZone(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/rectify")
            .header("X-API-Key", apiKey)
            .put(RequestBody.create("", MediaType.parse("application/json")))
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to notify zone: " + response);
      }
    }
  }

  /** DNSSEC key management */
  public List<Cryptokey> listCryptokeys(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/cryptokeys")
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to list cryptokeys: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(),
          objectMapper.getTypeFactory().constructCollectionType(List.class, Cryptokey.class));
    }
  }

  public Cryptokey createCryptokey(String zoneId, Cryptokey cryptokey) throws IOException {
    String json = objectMapper.writeValueAsString(cryptokey);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/cryptokeys")
            .header("X-API-Key", apiKey)
            .post(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to create cryptokey: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(), Cryptokey.class);
    }
  }

  public Cryptokey getCryptokey(String zoneId, int cryptokeyId) throws IOException {
    Request request =
        new Request.Builder()
            .url(
                baseUrl
                    + "/servers/"
                    + serverId
                    + "/zones/"
                    + zoneId
                    + "/cryptokeys/"
                    + cryptokeyId)
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to get cryptokey: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(), Cryptokey.class);
    }
  }

  public void modifyCryptokey(String zoneId, Cryptokey cryptokey) throws IOException {
    String json = objectMapper.writeValueAsString(cryptokey);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(
                baseUrl
                    + "/servers/"
                    + serverId
                    + "/zones/"
                    + zoneId
                    + "/cryptokeys/"
                    + cryptokey.getId())
            .header("X-API-Key", apiKey)
            .put(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to modify cryptokey: " + response);
      }
    }
  }

  public void deleteCryptokey(String zoneId, int cryptokeyId) throws IOException {
    Request request =
        new Request.Builder()
            .url(
                baseUrl
                    + "/servers/"
                    + serverId
                    + "/zones/"
                    + zoneId
                    + "/cryptokeys/"
                    + cryptokeyId)
            .header("X-API-Key", apiKey)
            .delete()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to delete cryptokey: " + response);
      }
    }
  }

  /** ZONE METADATA MANAGEMENT */
  public List<Metadata> listMetadata(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/metadata")
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to list metadata: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(),
          objectMapper.getTypeFactory().constructCollectionType(List.class, Metadata.class));
    }
  }

  public void createMetadata(String zoneId, Metadata metadata) throws IOException {
    String json = objectMapper.writeValueAsString(metadata);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/metadata")
            .header("X-API-Key", apiKey)
            .post(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to create metadata: " + response);
      }
    }
  }

  public Metadata getMetadata(String zoneId, String metadataKind) throws IOException {
    Request request =
        new Request.Builder()
            .url(
                baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/metadata/" + metadataKind)
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to get metadata: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(), Metadata.class);
    }
  }

  public Metadata modifyMetadata(String zoneId, String metadataKind, Metadata metadata)
      throws IOException {
    String json = objectMapper.writeValueAsString(metadata);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(
                baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/metadata/" + metadataKind)
            .header("X-API-Key", apiKey)
            .put(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to modify metadata: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(), Metadata.class);
    }
  }

  public void deleteMetadata(String zoneId, String metadataKind) throws IOException {
    Request request =
        new Request.Builder()
            .url(
                baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/metadata/" + metadataKind)
            .header("X-API-Key", apiKey)
            .delete()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to delete metadata: " + response);
      }
    }
  }

  /** TSIG KEY MANAGEMENT */
  public List<TSIGKey> listTSIGKeys() throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/tsigkeys")
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to list TSIG keys: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(),
          objectMapper.getTypeFactory().constructCollectionType(List.class, TSIGKey.class));
    }
  }

  public TSIGKey createTSIGKey(TSIGKey tsigKey) throws IOException {
    String json = objectMapper.writeValueAsString(tsigKey);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/tsigkeys")
            .header("X-API-Key", apiKey)
            .post(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to create TSIG key: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(), TSIGKey.class);
    }
  }

  public TSIGKey getTSIGKey(String tsigKeyId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/tsigkeys/" + tsigKeyId)
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to get TSIG key: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(), TSIGKey.class);
    }
  }

  public void deleteTSIGKey(String tsigKeyId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/tsigkeys/" + tsigKeyId)
            .header("X-API-Key", apiKey)
            .delete()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to delete TSIG key: " + response);
      }
    }
  }
}
