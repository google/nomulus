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
import google.registry.request.Action.Service;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Command to manually perform an authenticated RDAP query. */
@Parameters(separators = " =", commandDescription = "Manually perform an authenticated RDAP query")
public final class RdapQueryCommand implements CommandWithConnection {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /** Defines the RDAP query types, encapsulating their path logic and parameter requirements. */
  enum RdapQueryType {
    DOMAIN("/rdap/domain/%s"),
    DOMAIN_SEARCH("/rdap/domains", queryTerm -> ImmutableMap.of("name", queryTerm)),
    NAMESERVER("/rdap/nameserver/%s"),
    NAMESERVER_SEARCH("/rdap/nameservers", queryTerm -> ImmutableMap.of("name", queryTerm)),
    ENTITY("/rdap/entity/%s"),
    ENTITY_SEARCH("/rdap/entities", queryTerm -> ImmutableMap.of("fn", queryTerm)),
    HELP("/rdap/help", false);

    private final String pathFormat;
    private final boolean requiresQueryTerm;

    @SuppressWarnings("ImmutableEnumChecker")
    private final Function<String, ImmutableMap<String, String>> queryParametersFunction;

    /** Constructor for lookup queries that require a query term. */
    RdapQueryType(String pathFormat) {
      this(pathFormat, true, queryTerm -> ImmutableMap.of());
    }

    /** Constructor for search queries that require a query term. */
    RdapQueryType(
        String pathFormat, Function<String, ImmutableMap<String, String>> queryParametersFunction) {
      this(pathFormat, true, queryParametersFunction);
    }

    /** Constructor for queries that may not require a query term (e.g., HELP). */
    RdapQueryType(String pathFormat, boolean requiresQueryTerm) {
      this(pathFormat, requiresQueryTerm, queryTerm -> ImmutableMap.of());
    }

    RdapQueryType(
        String pathFormat,
        boolean requiresQueryTerm,
        Function<String, ImmutableMap<String, String>> queryParametersFunction) {
      this.pathFormat = pathFormat;
      this.requiresQueryTerm = requiresQueryTerm;
      this.queryParametersFunction = queryParametersFunction;
    }

    void validate(@Nullable String queryTerm) {
      checkArgument(
          requiresQueryTerm == (queryTerm != null),
          "A query term is %srequired for type %s",
          requiresQueryTerm ? "" : "not ",
          this.name());
    }

    String getQueryPath(@Nullable String queryTerm) {
      return getQueryParameters(queryTerm).isEmpty()
          ? String.format(pathFormat, queryTerm)
          : pathFormat;
    }

    ImmutableMap<String, String> getQueryParameters(@Nullable String queryTerm) {
      return queryParametersFunction.apply(queryTerm);
    }
  }

  @Parameter(names = "--type", description = "The type of RDAP query to perform.", required = true)
  private RdapQueryType type;

  @Parameter(
      description = "The main query term (e.g., a domain name or search pattern).",
      required = false)
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
    type.validate(queryTerm);

    String path = type.getQueryPath(queryTerm);
    ImmutableMap<String, String> queryParams = type.getQueryParameters(queryTerm);

    ServiceConnection pubapiConnection = defaultConnection.withService(Service.PUBAPI, useCanary);
    String rdapResponse = pubapiConnection.sendGetRequest(path, queryParams);

    JsonElement rdapJson = JsonParser.parseString(rdapResponse);
    logger.atInfo().log(GSON.toJson(rdapJson));
  }
}
