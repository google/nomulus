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

  /**
   * A simple data class to hold the path and query parameters for an RDAP request.
   *
   * <p>This is used as a return type for the {@code RdapQueryType.getRequestData} method to bundle
   * the two distinct return values into a single object.
   */
  private static class RequestData {
    final String path;
    final ImmutableMap<String, String> queryParams;

    private RequestData(String path, ImmutableMap<String, String> queryParams) {
      this.path = path;
      this.queryParams = queryParams;
    }

    static RequestData create(String path, ImmutableMap<String, String> queryParams) {
      return new RequestData(path, queryParams);
    }
  }

  /** Defines the RDAP query types, encapsulating their path logic and parameter requirements. */
  enum RdapQueryType {
    DOMAIN(true, "/rdap/domain/%s"),
    DOMAIN_SEARCH(true, "/rdap/domains", "name"),
    NAMESERVER(true, "/rdap/nameserver/%s"),
    NAMESERVER_SEARCH(true, "/rdap/nameservers", "name"),
    ENTITY(true, "/rdap/entity/%s"),
    ENTITY_SEARCH(true, "/rdap/entities", "fn"),
    HELP(false, "/rdap/help");

    private final boolean requiresQueryTerm;
    private final String pathFormat;
    private final String searchParamKey;

    RdapQueryType(boolean requiresQueryTerm, String pathFormat) {
      this(requiresQueryTerm, pathFormat, null);
    }

    RdapQueryType(boolean requiresQueryTerm, String pathFormat, String searchParamKey) {
      this.requiresQueryTerm = requiresQueryTerm;
      this.pathFormat = pathFormat;
      this.searchParamKey = searchParamKey;
    }

    /** Returns a RequestData object containing the path and query parameters for the request. */
    public RequestData getRequestData(String queryTerm) {
      if (requiresQueryTerm) {
        checkArgument(queryTerm != null, "A query term is required for the %s query.", this);
      } else {
        checkArgument(queryTerm == null, "The %s query does not take a query term.", this);
      }

      if (searchParamKey != null) {
        return RequestData.create(pathFormat, ImmutableMap.of(searchParamKey, queryTerm));
      } else if (requiresQueryTerm) {
        return RequestData.create(String.format(pathFormat, queryTerm), ImmutableMap.of());
      } else {
        return RequestData.create(pathFormat, ImmutableMap.of());
      }
    }
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

    RequestData requestData = type.getRequestData(queryTerm);

    ServiceConnection pubapiConnection =
        defaultConnection.withService(GkeService.PUBAPI, useCanary);
    String rdapResponse =
        pubapiConnection.sendGetRequest(requestData.path, requestData.queryParams);

    JsonElement rdapJson = JsonParser.parseString(rdapResponse);
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    logger.atInfo().log(gson.toJson(rdapJson));
  }
}
