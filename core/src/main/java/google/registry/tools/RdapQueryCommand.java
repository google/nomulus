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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action.GkeService;
import jakarta.inject.Inject; // <-- CORRECTED IMPORT
import java.io.IOException;
import java.util.List;

/** Command to manually perform an authenticated RDAP query. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final ImmutableSet<String> VALID_TYPES =
      ImmutableSet.of("domain", "registrar", "contact", "nameserver");

  @Parameter(description = "The object type and query term.", required = true)
  private List<String> mainParameters;

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
    checkArgument(
        mainParameters != null && mainParameters.size() == 2,
        "Usage: nomulus rdap_query <type> <query_term>\n"
            + "  <type> must be one of "
            + VALID_TYPES);

    String type = Ascii.toLowerCase(mainParameters.get(0));
    checkArgument(
        VALID_TYPES.contains(type),
        "Invalid object type '%s'. Must be one of %s",
        type,
        VALID_TYPES);

    String name = mainParameters.get(1);
    String path = String.format("/rdap/%s/%s", type, name);

    logger.atInfo().log("Starting RDAP query for path: %s", path);

    try {
      if (defaultConnection == null) {
        throw new IllegalStateException("ServiceConnection was not set by RegistryCli.");
      }
      ServiceConnection pubapiConnection =
          defaultConnection.withService(GkeService.PUBAPI, useCanary);

      String rdapResponse = pubapiConnection.sendGetRequest(path, ImmutableMap.of());
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
