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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Command to execute an authenticated RDAP query. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final ImmutableSet<String> VALID_TYPES =
      ImmutableSet.of("domain", "nameserver", "entity");

  @Parameter(description = "RDAP query string, in the format <type> <name>", required = true)
  private List<String> mainParameters;

  private ServiceConnection connection;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  @Override
  public void run() {
    if (mainParameters == null || mainParameters.size() != 2) {
      throw new IllegalArgumentException(
          "Usage: nomulus rdap_query <type> <query_term>\n"
              + "  <type> must be one of "
              + VALID_TYPES);
    }

    String type = Ascii.toLowerCase(mainParameters.get(0));
    if (!VALID_TYPES.contains(type)) {
      throw new IllegalArgumentException(
          String.format("Invalid object type '%s'. Must be one of %s", type, VALID_TYPES));
    }
    String name = mainParameters.get(1);
    String path = String.format("/rdap/%s/%s", type, name);

    try {
      String rdapResponse = connection.sendGetRequest(path, ImmutableMap.of());
      JsonElement rdapJson = JsonParser.parseString(rdapResponse);
      System.out.println(formatJsonElement(rdapJson, ""));
    } catch (IOException e) {
      System.err.println("Request failed for " + path + ": " + e.getMessage());
    }
  }

  /** Recursively formats a JSON element into indented key-value pairs. */
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
                  sb.append(indent).append(entry.getKey()).append(":\n");
                  sb.append(formatJsonElement(entry.getValue(), indent + "  "));
                }
              });
    } else if (element.isJsonArray()) {
      JsonArray array = element.getAsJsonArray();
      for (JsonElement arrayElement : array) {
        if (arrayElement.isJsonPrimitive()) {
          sb.append(indent).append("- ").append(arrayElement.getAsString()).append("\n");
        } else {
          sb.append(formatJsonElement(arrayElement, indent + "  "));
        }
      }
    } else if (element.isJsonPrimitive()) {
      sb.append(indent).append(element.getAsString()).append("\n");
    }
    return sb.toString();
  }
}
