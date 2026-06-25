// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.console.ConsolePermission.DOWNLOAD_DOMAINS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.annotations.Expose;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.console.User;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Returns a (paginated) list of domains for a particular registrar. */
@Action(
    service = Service.CONSOLE,
    path = ConsoleDomainListAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleDomainListAction extends ConsoleApiAction {

  public static final String PATH = "/console-api/domain-list";

  private static final int DEFAULT_RESULTS_PER_PAGE = 50;
  private static final String DOMAIN_QUERY_FILTER =
      " WHERE d.currentSponsorRegistrarId = :registrarId AND d.deletionTime >"
          + " :deletedAfterTime AND d.creationTime <= :createdBeforeTime";
  private static final String SEARCH_TERM_QUERY = " AND LOWER(d.domainName) LIKE :searchTerm";
  private static final String ORDER_BY_STATEMENT = " ORDER BY d.creationTime DESC";

  private final String registrarId;
  private final Optional<Instant> checkpointTime;
  private final int pageNumber;
  private final int resultsPerPage;
  private final Optional<Long> totalResults;
  private final Optional<String> searchTerm;

  @Inject
  public ConsoleDomainListAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registrarId") String registrarId,
      @Parameter("checkpointTime") Optional<Instant> checkpointTime,
      @Parameter("pageNumber") Optional<Integer> pageNumber,
      @Parameter("resultsPerPage") Optional<Integer> resultsPerPage,
      @Parameter("totalResults") Optional<Long> totalResults,
      @Parameter("searchTerm") Optional<String> searchTerm) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.checkpointTime = checkpointTime;
    this.pageNumber = pageNumber.orElse(0);
    this.resultsPerPage = resultsPerPage.orElse(DEFAULT_RESULTS_PER_PAGE);
    this.totalResults = totalResults;
    this.searchTerm = searchTerm;
  }

  @Override
  protected void getHandler(User user) {
    checkPermission(user, registrarId, DOWNLOAD_DOMAINS);
    checkArgument(
        resultsPerPage > 0 && resultsPerPage <= 500,
        "Results per page must be between 1 and 500 inclusive");
    checkArgument(pageNumber >= 0, "Page number must be non-negative");
    tm().transact(this::runInTransaction);
  }

  private void runInTransaction() {
    int numResultsToSkip = resultsPerPage * pageNumber;

    // We have to use a constant checkpoint time in order to have stable pagination, since domains
    // can be constantly created or deleted
    Instant checkpoint = checkpointTime.orElseGet(tm()::getTxTime);
    CreateAutoTimestamp checkpointTimestamp = CreateAutoTimestamp.create(checkpoint);
    // Don't compute the number of total results over and over if we don't need to
    long actualTotalResults =
        totalResults.orElseGet(
            () ->
                createCountQuery()
                    .setParameter("registrarId", registrarId)
                    .setParameter("createdBeforeTime", checkpointTimestamp)
                    .setParameter("deletedAfterTime", checkpoint)
                    .getSingleResult());
    List<Domain> domains =
        createDomainQuery()
            .setParameter("registrarId", registrarId)
            .setParameter("createdBeforeTime", checkpointTimestamp)
            .setParameter("deletedAfterTime", checkpoint)
            .setFirstResult(numResultsToSkip)
            .setMaxResults(resultsPerPage)
            .getResultList();

    ImmutableList<ConsoleDomainInfo> domainInfos;
    if (domains.isEmpty()) {
      domainInfos = ImmutableList.of();
    } else {
      ImmutableList<String> repoIds =
          domains.stream().map(Domain::getRepoId).collect(ImmutableList.toImmutableList());
      List<Domain> domainsWithStatuses =
          tm().query(
                  "SELECT DISTINCT d FROM Domain d "
                      + "LEFT JOIN FETCH d.statuses "
                      + "WHERE d.repoId IN (:repoIds)",
                  Domain.class)
              .setParameter("repoIds", repoIds)
              .getResultList();

      Map<String, Domain> statusMap =
          domainsWithStatuses.stream().collect(Collectors.toMap(Domain::getRepoId, d -> d));

      domainInfos =
          domains.stream()
              .map(
                  d -> {
                    Domain domainWithStatuses = statusMap.getOrDefault(d.getRepoId(), d);
                    return ConsoleDomainInfo.fromDomain(domainWithStatuses);
                  })
              .collect(ImmutableList.toImmutableList());
    }

    consoleApiParams
        .response()
        .setPayload(
            consoleApiParams
                .gson()
                .toJson(new DomainListResult(domainInfos, checkpoint, actualTotalResults)));
    consoleApiParams.response().setStatus(SC_OK);
  }

  /** Creates the query to get the total number of matching domains, interpolating as necessary. */
  private TypedQuery<Long> createCountQuery() {
    String queryString = "SELECT COUNT(d) FROM Domain d" + DOMAIN_QUERY_FILTER;
    if (searchTerm.isPresent() && !searchTerm.get().isEmpty()) {
      return tm().query(queryString + SEARCH_TERM_QUERY, Long.class)
          .setParameter("searchTerm", String.format("%%%s%%", Ascii.toLowerCase(searchTerm.get())));
    }
    return tm().query(queryString, Long.class);
  }

  /** Creates the query to retrieve the matching domains themselves, interpolating as necessary. */
  private TypedQuery<Domain> createDomainQuery() {
    String query = "SELECT d FROM Domain d" + DOMAIN_QUERY_FILTER;
    if (searchTerm.isPresent() && !searchTerm.get().isEmpty()) {
      return tm().query(query + SEARCH_TERM_QUERY + ORDER_BY_STATEMENT, Domain.class)
          .setParameter("searchTerm", String.format("%%%s%%", Ascii.toLowerCase(searchTerm.get())));
    }
    return tm().query(query + ORDER_BY_STATEMENT, Domain.class);
  }

  public static final class ConsoleDomainInfo {
    @Expose String domainName;
    @Expose CreateAutoTimestamp creationTime;
    @Expose Instant registrationExpirationTime;
    @Expose String currentSponsorRegistrarId;
    @Expose Set<String> statuses;

    public String getDomainName() {
      return domainName;
    }

    public CreateAutoTimestamp getCreationTime() {
      return creationTime;
    }

    public Instant getRegistrationExpirationTime() {
      return registrationExpirationTime;
    }

    public String getCurrentSponsorRegistrarId() {
      return currentSponsorRegistrarId;
    }

    public Set<String> getStatuses() {
      return statuses;
    }

    static ConsoleDomainInfo fromDomain(Domain domain) {
      ConsoleDomainInfo info = new ConsoleDomainInfo();
      info.domainName = domain.getDomainName();
      info.creationTime = CreateAutoTimestamp.create(domain.getCreationTime());
      info.registrationExpirationTime = domain.getRegistrationExpirationTime();
      info.currentSponsorRegistrarId = domain.getCurrentSponsorRegistrarId();
      info.statuses =
          domain.getStatusValues().stream()
              .map(StatusValue::name)
              .collect(ImmutableSet.toImmutableSet());
      return info;
    }
  }

  /** Container result class that allows for pagination. */
  @VisibleForTesting
  static final class DomainListResult {
    @Expose List<ConsoleDomainInfo> domains;
    @Expose Instant checkpointTime;
    @Expose long totalResults;

    private DomainListResult(
        List<ConsoleDomainInfo> domains, Instant checkpointTime, long totalResults) {
      this.domains = domains;
      this.checkpointTime = checkpointTime;
      this.totalResults = totalResults;
    }
  }
}
