// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.tools;

import static com.google.common.base.Preconditions.checkState;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action.Service;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;

/** Command to manually perform an authenticated RDAP query. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  enum RdapQueryType {
    DOMAIN_LOOKUP("/rdap/domain/%s"),
    DOMAIN_SEARCH("/rdap/domains", "name"),
    NAMESERVER_LOOKUP("/rdap/nameserver/%s"),
    NAMESERVER_SEARCH("/rdap/nameservers", "name"),
    ENTITY_LOOKUP("/rdap/entity/%s"),
    ENTITY_SEARCH("/rdap/entities", "fn");

    private final String pathFormat;
    private final Optional<String> searchParamKey;

    RdapQueryType(String pathFormat) {
      this(pathFormat, null);
    }

    RdapQueryType(String pathFormat, @Nullable String searchParamKey) {
      this.pathFormat = pathFormat;
      this.searchParamKey = Optional.ofNullable(searchParamKey);
    }

    String getQueryPath(String queryTerm) {
      // Logic is now simplified as suggested.
      return searchParamKey.isPresent() ? pathFormat : String.format(pathFormat, queryTerm);
    }

    ImmutableMap<String, String> getQueryParameters(String queryTerm) {
      return searchParamKey.map(key -> ImmutableMap.of(key, queryTerm)).orElse(ImmutableMap.of());
    }
  }

  @Parameter(names = "--type", description = "The type of RDAP query to perform.", required = true)
  private RdapQueryType type;

  @Parameter(
      description = "The main query term (e.g., a domain name or search pattern).",
      required = true)
  private String queryTerm;

  @Inject ServiceConnection defaultConnection;

  @Inject
  @Config("useCanary")
  boolean useCanary;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.defaultConnection = connection;
  }

  @Override
  public void run() throws IOException {
    checkState(defaultConnection != null, "ServiceConnection was not set by RegistryCli.");

    String path = type.getQueryPath(queryTerm);
    ImmutableMap<String, String> queryParams = type.getQueryParameters(queryTerm);

    logger.atInfo().log("Starting RDAP query for path: %s with params: %s", path, queryParams);

    ServiceConnection pubapiConnection = defaultConnection.withService(Service.PUBAPI, useCanary);
    String rdapResponse = pubapiConnection.sendGetRequest(path, queryParams);

    JsonElement rdapJson = JsonParser.parseString(rdapResponse);
    // Print to System.out to allow for simple testing with CommandTestCase helpers.
    System.out.println(GSON.toJson(rdapJson));

    logger.atInfo().log("Successfully completed RDAP query.");
  }
}
