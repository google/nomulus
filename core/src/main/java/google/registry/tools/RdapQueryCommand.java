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

// In google.registry.tools.RdapQueryCommand.java
// ...
package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter; // Keep Splitter
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action.GkeService;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Command to manually perform an authenticated RDAP query. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final ImmutableSet<String> LOOKUP_TYPES =
      ImmutableSet.of("domain", "nameserver", "entity");
  private static final ImmutableSet<String> SEARCH_TYPES =
      ImmutableSet.of("domains", "nameservers", "entities");

  @Parameter(
      description =
          "The RDAP path segments. For lookups: '<type> <query_term>' (e.g., 'domain gustav.dev'). "
              + "For searches: '<search_type>' followed by '--params' (e.g., 'domains').",
      required = true)
  private List<String> mainParameters = new ArrayList<>();

  @Parameter(
      names = "--params",
      description =
          "Optional search parameters in key=value format, comma-separated. "
              + "E.g., '--params name=gusta*.dev,nsLdhName=ns1.example.com'. "
              + "Only used with search types.")
  private List<String> params = new ArrayList<>(); // JCommander will populate this List<String>

  private ServiceConnection defaultConnection;

  @Inject
  @Config("useCanary")
  boolean useCanary;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.defaultConnection = connection;
  }

  // Removed KeyValueValidator class, validation will be done in run()

  @Override
  public void run() {
    checkArgument(!mainParameters.isEmpty(), "Missing RDAP path argument.");

    String path;
    ImmutableMap<String, String> queryParams = ImmutableMap.of();

    String firstParam = Ascii.toLowerCase(mainParameters.get(0));

    if (SEARCH_TYPES.contains(firstParam)) {
      // Handle search: /rdap/{search_type}?{params}
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
      // Handle lookup: /rdap/{type}/{name}
      checkArgument(
          mainParameters.size() == 2,
          "Lookup type '%s' requires exactly one query term.",
          firstParam);
      checkArgument(params.isEmpty(), "Lookup queries do not accept --params.");
      String type = firstParam;
      String name = mainParameters.get(1);
      path = String.format("/rdap/%s/%s", type, name);
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

      Gson gson = new GsonBuilder().setPrettyPrinting().create();
      System.out.println(gson.toJson(rdapJson));

      logger.atInfo().log("Successfully completed RDAP query for path: %s", path);
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Request failed for path: %s", path);
      System.err.println("Request failed for " + path + ": " + e.getMessage());
    }
  }
}
