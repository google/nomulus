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
import com.google.common.collect.ImmutableMap;
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

/** Command to manually perform an authenticated RDAP query for any path. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

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

    // Universally construct the path from all main parameters.
    // E.g., ["domain", "foo.dev"] becomes "/rdap/domain/foo.dev"
    String path = "/rdap/" + String.join("/", mainParameters);

    // Universally parse all optional --params into a map.
    ImmutableMap<String, String> queryParams =
        params.stream()
            .map(
                p -> {
                  List<String> parts = Splitter.on('=').limit(2).splitToList(p);
                  checkArgument(parts.size() == 2, "Invalid parameter format: %s", p);
                  return parts;
                })
            .collect(toImmutableMap(parts -> parts.get(0), parts -> parts.get(1)));

    logger.atInfo().log("Starting RDAP query for path: %s with params: %s", path, queryParams);

    try {
      if (defaultConnection == null) {
        throw new IllegalStateException("ServiceConnection was not set by RegistryCli.");
      }
      // This is the core fix: always create a new connection targeting the PUBAPI service.
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
