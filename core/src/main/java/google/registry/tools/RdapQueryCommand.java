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

import static com.google.common.base.Preconditions.checkArgument;
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
import google.registry.request.Action.GkeService;
import jakarta.inject.Inject;
import java.io.IOException;

/** Command to manually perform an authenticated RDAP query. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  enum RdapQueryType {
    DOMAIN,
    DOMAINSEARCH,
    NAMESERVER,
    NAMESERVERSEARCH,
    ENTITY,
    ENTITYSEARCH,
    HELP
  }

  @Parameter(names = "--type", description = "The type of RDAP query to perform.", required = true)
  private RdapQueryType type;

  @Parameter(
      description = "The main query term (e.g., a domain name or search pattern).",
      required = false)
  private String queryTerm;

  private ServiceConnection defaultConnection;

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

    String path;
    ImmutableMap<String, String> queryParams;

    switch (type) {
      case DOMAIN:
        checkArgument(queryTerm != null, "A query term is required for a DOMAIN lookup.");
        path = "/rdap/domain/" + queryTerm;
        queryParams = ImmutableMap.of();
        break;
      case DOMAINSEARCH:
        checkArgument(queryTerm != null, "A search term is required for a DOMAINSEARCH.");
        path = "/rdap/domains";
        queryParams = ImmutableMap.of("name", queryTerm);
        break;
      case NAMESERVER:
        checkArgument(queryTerm != null, "A query term is required for a NAMESERVER lookup.");
        path = "/rdap/nameserver/" + queryTerm;
        queryParams = ImmutableMap.of();
        break;
      case NAMESERVERSEARCH:
        checkArgument(queryTerm != null, "A search term is required for a NAMESERVERSEARCH.");
        path = "/rdap/nameservers";
        queryParams = ImmutableMap.of("name", queryTerm);
        break;
      case ENTITY:
        checkArgument(queryTerm != null, "A query term is required for an ENTITY lookup.");
        path = "/rdap/entity/" + queryTerm;
        queryParams = ImmutableMap.of();
        break;
      case ENTITYSEARCH:
        checkArgument(queryTerm != null, "A search term is required for an ENTITYSEARCH.");
        path = "/rdap/entities";
        queryParams = ImmutableMap.of("fn", queryTerm);
        break;
      case HELP:
        checkArgument(queryTerm == null, "The HELP query does not take a query term.");
        path = "/rdap/help";
        queryParams = ImmutableMap.of();
        break;
      default:
        throw new IllegalStateException("Unsupported query type: " + type);
    }

    ServiceConnection pubapiConnection =
        defaultConnection.withService(GkeService.PUBAPI, useCanary);
    String rdapResponse = pubapiConnection.sendGetRequest(path, queryParams);

    JsonElement rdapJson = JsonParser.parseString(rdapResponse);
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    logger.atInfo().log(gson.toJson(rdapJson));
  }
}
