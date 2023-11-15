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

import static google.registry.model.console.ConsolePermission.DOWNLOAD_DOMAINS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.console.User;
import google.registry.model.domain.Domain;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.ui.server.registrar.JsonGetAction;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.persistence.TypedQuery;
import org.joda.time.DateTime;

/** Returns a (paginated) list of domains for a particular registrar. */
@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleDomainListAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleDomainListAction implements JsonGetAction {

  public static final String PATH = "/console-api/domain-list";

  private static final int DEFAULT_RESULTS_PER_PAGE = 50;
  private static final String DOMAIN_QUERY_TEMPLATE =
      "FROM Domain WHERE currentSponsorRegistrarId = :registrarId AND deletionTime >"
          + " :deletedAfterTime AND creationTime <= :createdBeforeTime";
  private static final String SEARCH_TERM_QUERY = " AND LOWER(domainName) LIKE :searchTerm";
  private static final String ORDER_BY_STATEMENT = " ORDER BY creationTime DESC";

  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private final String registrarId;
  private final Optional<DateTime> checkpointTime;
  private final int pageNumber;
  private final int resultsPerPage;
  private final Optional<Long> totalResults;
  private final Optional<String> searchTerm;

  @Inject
  public ConsoleDomainListAction(
      AuthResult authResult,
      Response response,
      Gson gson,
      @Parameter("registrarId") String registrarId,
      @Parameter("checkpointTime") Optional<DateTime> checkpointTime,
      @Parameter("pageNumber") Optional<Integer> pageNumber,
      @Parameter("resultsPerPage") Optional<Integer> resultsPerPage,
      @Parameter("totalResults") Optional<Long> totalResults,
      @Parameter("searchTerm") Optional<String> searchTerm) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.registrarId = registrarId;
    this.checkpointTime = checkpointTime;
    this.pageNumber = pageNumber.orElse(0);
    this.resultsPerPage = resultsPerPage.orElse(DEFAULT_RESULTS_PER_PAGE);
    this.totalResults = totalResults;
    this.searchTerm = searchTerm;
  }

  @Override
  public void run() {
    User user = authResult.userAuthInfo().get().consoleUser().get();
    if (!user.getUserRoles().hasPermission(registrarId, DOWNLOAD_DOMAINS)) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }

    if (resultsPerPage < 1 || resultsPerPage > 500) {
      writeBadRequest("Results per page must be between 1 and 500 inclusive");
      return;
    }
    if (pageNumber < 0) {
      writeBadRequest("Page number must be non-negative");
      return;
    }

    tm().transact(this::runInTransaction);
  }

  private void runInTransaction() {
    int numResultsToSkip = resultsPerPage * pageNumber;

    // We have to use a constant checkpoint time in order to have stable pagination, since domains
    // can be constantly created or deleted
    DateTime checkpoint = checkpointTime.orElseGet(tm()::getTransactionTime);
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
    response.setPayload(gson.toJson(new DomainListResult(domains, checkpoint, actualTotalResults)));
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }

  /** Creates the query to get the total number of matching domains, interpolating as necessary. */
  private TypedQuery<Long> createCountQuery() {
    String queryString = "SELECT COUNT(*) " + DOMAIN_QUERY_TEMPLATE;
    if (searchTerm.isPresent() && !searchTerm.get().isEmpty()) {
      return tm().query(queryString + SEARCH_TERM_QUERY, Long.class)
          .setParameter("searchTerm", String.format("%%%s%%", Ascii.toLowerCase(searchTerm.get())));
    }
    return tm().query(queryString, Long.class);
  }

  /** Creates the query to retrieve the matching domains themselves, interpolating as necessary. */
  private TypedQuery<Domain> createDomainQuery() {
    if (searchTerm.isPresent() && !searchTerm.get().isEmpty()) {
      return tm().query(
              DOMAIN_QUERY_TEMPLATE + SEARCH_TERM_QUERY + ORDER_BY_STATEMENT, Domain.class)
          .setParameter("searchTerm", String.format("%%%s%%", Ascii.toLowerCase(searchTerm.get())));
    }
    return tm().query(DOMAIN_QUERY_TEMPLATE + ORDER_BY_STATEMENT, Domain.class);
  }

  private void writeBadRequest(String message) {
    response.setPayload(message);
    response.setStatus(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
  }

  /** Container result class that allows for pagination. */
  @VisibleForTesting
  static final class DomainListResult {
    @Expose List<Domain> domains;
    @Expose DateTime checkpointTime;
    @Expose long totalResults;

    private DomainListResult(List<Domain> domains, DateTime checkpointTime, long totalResults) {
      this.domains = domains;
      this.checkpointTime = checkpointTime;
      this.totalResults = totalResults;
    }
  }
}
