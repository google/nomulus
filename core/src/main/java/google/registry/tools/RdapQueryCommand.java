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
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

/** Command to manually perform an authenticated RDAP query for any path. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final ImmutableSet<String> LOOKUP_TYPES =
      ImmutableSet.of("domain", "nameserver", "entity", "autnum", "ip", "help");
  private static final ImmutableSet<String> SEARCH_TYPES =
      ImmutableSet.of("domains", "nameservers", "entities");

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
    checkArgument(!mainParameters.isEmpty(), "Missing RDAP path segments.");

    String path;
    ImmutableMap<String, String> queryParams = ImmutableMap.of();

    String firstParam = Ascii.toLowerCase(mainParameters.get(0));

    if (SEARCH_TYPES.contains(firstParam)) {
      checkArgument(
          mainParameters.size() == 1,
          "Search types like '%s' do not accept additional positional arguments.",
          firstParam);
      path = "/rdap/" + firstParam;
      if (!params.isEmpty()) {
        queryParams =
            params.stream()
                .map(
                    p -> {
                      List<String> parts = Splitter.on('=').limit(2).splitToList(p);
                      checkArgument(parts.size() == 2, "Invalid parameter format: %s", p);
                      return parts;
                    })
                .collect(toImmutableMap(parts -> parts.get(0), parts -> parts.get(1)));
      } else {
        logger.atWarning().log(
            "Performing an RDAP search for '%s' without any query parameters.", firstParam);
      }
    } else if (LOOKUP_TYPES.contains(firstParam)) {
      checkArgument(params.isEmpty(), "Lookup queries do not accept --params.");
      String type = firstParam;
      String name = "";
      if (mainParameters.size() > 1) {
        name = mainParameters.get(1);
        checkArgument(
            mainParameters.size() == 2, "Lookup type '%s' requires exactly one query term.", type);
      } else if (!type.equals("help")) {
        throw new IllegalArgumentException(String.format("Lookup type '%s' requires a query term.", type));
      }
      path = String.format("/rdap/%s%s", type, name.isEmpty() ? "" : "/" + name);
    } else {
      throw new IllegalArgumentException(
          "Usage: nomulus rdap_query <type> <query_term> OR nomulus rdap_query <search_type> --params <key=value>[,...]\n"
              + "  Lookup types: "
              + LOOKUP_TYPES
              + "\n  Search types: "
              + SEARCH_TYPES);
    }

    logger.atInfo().log("Starting RDAP query for path: %s with params: %s", path, queryParams);

    try {
      if (defaultConnection == null) {
        throw new IllegalStateException("ServiceConnection was not set by RegistryCli.");
      }
      ServiceConnection pubapiConnection =
          defaultConnection.withService(GkeService.PUBAPI, useCanary);

      String rdapResponse = pubapiConnection.sendGetRequest(path, queryParams);
      JsonElement rdapJson = JsonParser.parseString(rdapResponse);

      // This now calls the custom formatter instead of Gson.
      System.out.println(formatJsonElement(rdapJson, ""));

      logger.atInfo().log("Successfully completed RDAP query for path: %s", path);
    } catch (IOException e) {
      // Always log the full exception for backend records.
      logger.atSevere().withCause(e).log("Request failed for path: %s", path);

      String errorMessage = e.getMessage();
      String userFriendlyError;

      if (errorMessage != null) {
        try {
          int jsonStartIndex = errorMessage.indexOf('{');
          if (jsonStartIndex != -1) {
            String jsonString = errorMessage.substring(jsonStartIndex);
            JsonElement errorJsonElement = JsonParser.parseString(jsonString);

            if (errorJsonElement.isJsonObject()) {
              JsonObject errorObj = errorJsonElement.getAsJsonObject();
              String title = errorObj.has("title") ? errorObj.get("title").getAsString() : "Error";
              int errorCode = errorObj.has("errorCode") ? errorObj.get("errorCode").getAsInt() : -1;
              String description = "";
              if (errorObj.has("description")) {
                JsonElement descElement = errorObj.get("description");
                if (descElement.isJsonArray()) {
                  StringBuilder sb = new StringBuilder();
                  for (JsonElement element : descElement.getAsJsonArray()) {
                    if (sb.length() > 0) sb.append("\n  ");
                    sb.append(element.getAsString());
                  }
                  description = sb.toString();
                } else if (descElement.isJsonPrimitive()) {
                  description = descElement.getAsString();
                }
              }

              StringBuilder improvedError = new StringBuilder();
              improvedError.append("RDAP Request Failed (Code ").append(errorCode).append("): ");
              improvedError.append(title);
              if (!Strings.isNullOrEmpty(description)) {
                improvedError.append("\n  Description: ").append(description);
              }
              userFriendlyError = improvedError.toString();
            } else {
              userFriendlyError = "Request failed for " + path + ": " + errorMessage;
            }
          } else {
            if (errorMessage.contains(": 501 Not Implemented")) {
              userFriendlyError =
                  "RDAP Request Failed (Code 501): Not Implemented\n"
                      + "  Description: The query for '" + path + "' was understood, but is not implemented by this registry.";
            } else if (errorMessage.contains(": 404 Not Found")) {
              userFriendlyError =
                  "RDAP Request Failed (Code 404): Not Found\n"
                      + "  Description: The resource at path '" + path + "' does not exist or no results matched the query.";
            } else if (errorMessage.contains(": 422 Unprocessable Entity")) {
              userFriendlyError =
                  "RDAP Request Failed (Code 422): Unprocessable Entity\n"
                      + "  Description: The server understood the request, but cannot process the included entities.";
            } else if (errorMessage.contains(": 500 Internal Server Error")) {
              userFriendlyError =
                  "RDAP Request Failed (Code 500): Internal Server Error\n"
                      + "  Description: An unexpected error occurred on the server. Check server logs for details.";
            } else if (errorMessage.contains(": 503 Service Unavailable")) {
              userFriendlyError =
                  "RDAP Request Failed (Code 503): Service Unavailable\n"
                      + "  Description: The RDAP service is temporarily unavailable. Please try again later.";
            } else {
              userFriendlyError = "Request failed for " + path + ": " + errorMessage.substring(0, Math.min(errorMessage.length(), 150));
            }
          }
        } catch (Exception jsonEx) {
          logger.atWarning().withCause(jsonEx).log("Failed to parse error response as JSON, showing raw error.");
          userFriendlyError = "Request failed for " + path + ": " + errorMessage;
        }
      } else {
        userFriendlyError = "Request failed for " + path + ": " + "No error message available.";
      }
      System.err.println(userFriendlyError);
    }
  }

  /** Recursively formats a JsonElement into a human-readable, indented, key-value string. */
  private String formatJsonElement(JsonElement element, String indent) {
    StringBuilder sb = new StringBuilder();
    if (element == null || element.isJsonNull()) {
      // Omit nulls for cleaner output
    } else if (element.isJsonObject()) {
      JsonObject obj = element.getAsJsonObject();
      obj.entrySet().stream()
          .sorted(Map.Entry.comparingByKey()) // Sort keys for consistent output
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
      sb.append(indent).append(element.getAsString()).append("\n"); // Handle top-level or standalone primitives
    }
    return sb.toString();
  }
}
