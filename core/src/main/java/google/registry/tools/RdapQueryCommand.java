// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action.GkeService;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Command to manually perform an RDAP query for any path. */
@Parameters(separators = " =", commandDescription = "Manually perform an RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  @Parameter(
      description = "The ordered RDAP path segments that form the path (e.g., 'domain foo.dev').",
      required = true)
  private List<String> mainParameters = new ArrayList<>();

  @Parameter(
      names = "--params",
      description = "Optional search parameters in key=value format (e.g., 'name=example*.com').",
      variableArity = true)
  private List<String> params = new ArrayList<>();

  private ServiceConnection defaultConnection;

  @Inject
  @Config("useCanary")
  boolean useCanary;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.defaultConnection = connection;
  }

  @Override
  public void run() {

    String path = "/rdap/" + String.join("/", mainParameters);

    ImmutableMap<String, String> queryParams = parseParams(params);

    try {
      if (defaultConnection == null) {
        throw new IllegalStateException("ServiceConnection was not set by RegistryCli.");
      }
      ServiceConnection pubapiConnection =
          defaultConnection.withService(GkeService.PUBAPI, useCanary);

      String rdapResponse = pubapiConnection.sendGetRequest(path, queryParams);
      JsonElement rdapJson = JsonParser.parseString(rdapResponse);

      System.out.println(formatJsonElement(rdapJson, ""));

    } catch (IOException e) {
      handleIOException(path, e);
    }
  }

  /** Parses the --params list into an ImmutableMap of query parameters. */
  private ImmutableMap<String, String> parseParams(List<String> paramsList) {
    return paramsList.stream()
        .map(
            p -> {
              List<String> parts = Splitter.on('=').limit(2).splitToList(p);
              checkArgument(parts.size() == 2, "Invalid parameter format: %s", p);
              return parts;
            })
        .collect(toImmutableMap(parts -> parts.get(0), parts -> parts.get(1)));
  }

  /** Handles and formats IOException, printing a user-friendly message to System.err. */
  private void handleIOException(String path, IOException e) {

    String errorMessage = e.getMessage();
    String userFriendlyError = formatUserFriendlyError(path, errorMessage);
    System.err.println(userFriendlyError);
  }

  /** Formats a user-friendly error message from the IOException details. */
  private String formatUserFriendlyError(String path, String errorMessage) {
    if (errorMessage == null) {
      return "Request failed for " + path + ": No error message available.";
    }

    Optional<JsonObject> errorJson = parseErrorJson(errorMessage);

    if (errorJson.isPresent()) {
      return formatJsonError(errorJson.get());
    } else {
      return formatFallbackError(path, errorMessage);
    }
  }

  /** Attempts to parse a JSON object from the IOException message. */
  private Optional<JsonObject> parseErrorJson(String errorMessage) {
    try {
      int jsonStartIndex = errorMessage.indexOf('{');
      if (jsonStartIndex != -1) {
        String jsonString = errorMessage.substring(jsonStartIndex);
        JsonElement errorJsonElement = JsonParser.parseString(jsonString);
        if (errorJsonElement.isJsonObject()) {
          return Optional.of(errorJsonElement.getAsJsonObject());
        }
      }
    } catch (Exception jsonEx) {
      System.err.println(
          "RDAP Internal Warning: Failed to parse error JSON: " + jsonEx.getMessage());
    }
    return Optional.empty();
  }

  /** Formats a user-friendly error string from a parsed JSON error object. */
  private String formatJsonError(JsonObject errorObj) {
    String title = errorObj.has("title") ? errorObj.get("title").getAsString() : "Error";
    int errorCode = errorObj.has("errorCode") ? errorObj.get("errorCode").getAsInt() : -1;
    String description = parseJsonDescription(errorObj);

    StringBuilder improvedError =
        new StringBuilder()
            .append("RDAP Request Failed (Code ")
            .append(errorCode)
            .append("): ")
            .append(title);
    if (!Strings.isNullOrEmpty(description)) {
      improvedError.append("\n  Description: ").append(description);
    }
    return improvedError.toString();
  }

  /** Extracts and formats the 'description' field from a JSON error object. */
  private String parseJsonDescription(JsonObject errorObj) {
    if (!errorObj.has("description")) {
      return "";
    }
    JsonElement descElement = errorObj.get("description");
    if (descElement.isJsonArray()) {
      StringBuilder sb = new StringBuilder();
      for (JsonElement element : descElement.getAsJsonArray()) {
        if (sb.length() > 0) sb.append("\n  ");
        sb.append(element.getAsString());
      }
      return sb.toString();
    } else if (descElement.isJsonPrimitive()) {
      return descElement.getAsString();
    }
    return "";
  }

  /** Formats a fallback error message when JSON parsing fails. */
  private String formatFallbackError(String path, String errorMessage) {
    if (errorMessage.contains(": 501 Not Implemented")) {
      return "RDAP Request Failed (Code 501): Not Implemented\n"
          + "  Description: The query for '"
          + path
          + "' was understood, but is not implemented by this registry.";
    } else if (errorMessage.contains(": 404 Not Found")) {
      return "RDAP Request Failed (Code 404): Not Found\n"
          + "  Description: The resource at path '"
          + path
          + "' does not exist or no results matched the query.";
    } else if (errorMessage.contains(": 422 Unprocessable Entity")) {
      return "RDAP Request Failed (Code 422): Unprocessable Entity\n"
          + "  Description: The server understood the request, but cannot process the"
          + " included entities.";
    } else if (errorMessage.contains(": 500 Internal Server Error")) {
      return "RDAP Request Failed (Code 500): Internal Server Error\n"
          + "  Description: An unexpected error occurred on the server. Check server logs"
          + " for details.";
    } else if (errorMessage.contains(": 503 Service Unavailable")) {
      return "RDAP Request Failed (Code 503): Service Unavailable\n"
          + "  Description: The RDAP service is temporarily unavailable. Please try again later.";
    } else {
      String rawMessage = errorMessage.substring(0, Math.min(errorMessage.length(), 150));
      return "Request failed for " + path + ": " + rawMessage;
    }
  }

  /** Recursively formats a JsonElement into a human-readable, indented, key-value string. */
  private String formatJsonElement(JsonElement element, String indent) {
    StringBuilder sb = new StringBuilder();
    if (element == null || element.isJsonNull()) {
    } else if (element.isJsonObject()) {
      JsonObject obj = element.getAsJsonObject();
      obj.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                if (!entry.getValue().isJsonNull()) {
                  sb.append(indent).append(entry.getKey()).append(":");
                  JsonElement child = entry.getValue();
                  if (child.isJsonPrimitive()) {
                    sb.append(" ").append(child.getAsString()).append("\n");
                  } else {
                    sb.append("\n").append(formatJsonElement(child, indent + "  "));
                  }
                }
              });
    } else if (element.isJsonArray()) {
      JsonArray array = element.getAsJsonArray();
      for (JsonElement item : array) {
        if (item.isJsonPrimitive()) {
          sb.append(indent).append("- ").append(item.getAsString()).append("\n");
        } else if (item.isJsonObject()) {
          sb.append(indent).append("-\n").append(formatJsonElement(item, indent + "  "));
        } else if (item.isJsonArray()) {
          sb.append(indent).append("-\n").append(formatJsonElement(item, indent + "  "));
        }
      }
    } else if (element.isJsonPrimitive()) {
      sb.append(indent).append(element.getAsString()).append("\n");
    }
    return sb.toString();
  }
}
