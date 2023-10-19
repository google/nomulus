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
import google.registry.util.DateTimeUtils;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** Returns a (paginated) list of domains for a particular registrar. */
@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleDomainListAction.PATH,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleDomainListAction implements JsonGetAction {

  public static final String PATH = "/console-api/domain-list";

  private static final int DEFAULT_RESULTS_PER_PAGE = 50;
  private static final String DOMAIN_QUERY_TEMPLATE =
      "FROM Domain WHERE currentSponsorRegistrarId = :registrarId AND deletionTime = :endOfTime"
          + " AND creationTime <= :createdBeforeTime";

  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private final String registrarId;
  private final DomainListRequest domainListRequest;

  @Inject
  public ConsoleDomainListAction(
      AuthResult authResult,
      Response response,
      Gson gson,
      @Parameter("registrarId") String registrarId,
      @Parameter("domainListRequest") DomainListRequest domainListRequest) {
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.registrarId = registrarId;
    this.domainListRequest = domainListRequest;
  }

  @Override
  public void run() {
    User user = authResult.userAuthInfo().get().consoleUser().get();
    if (!user.getUserRoles().hasPermission(registrarId, DOWNLOAD_DOMAINS)) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }

    if (domainListRequest.resultsPerPage < 1 || domainListRequest.resultsPerPage > 500) {
      writeBadRequest("Results per page must be between 1 and 500 inclusive");
      return;
    }
    if (domainListRequest.pageNumber < 0) {
      writeBadRequest("Page number must be non-negative");
      return;
    }

    tm().transact(this::runInTransaction);
  }

  private void runInTransaction() {
    int numResultsToSkip = domainListRequest.resultsPerPage * domainListRequest.pageNumber;

    // We have to use a constant created-before time in order to have stable pagination, since new
    // domains can be constantly created
    CreateAutoTimestamp createdBeforeTime;
    if (domainListRequest.createdBeforeTime == null) {
      createdBeforeTime = CreateAutoTimestamp.create(tm().getTransactionTime());
    } else {
      createdBeforeTime = CreateAutoTimestamp.create(domainListRequest.createdBeforeTime);
    }
    // Don't compute the number of total results over and over if we don't need to
    long totalResults;
    if (domainListRequest.totalResults == null) {
      totalResults =
          tm().query("SELECT COUNT(*) " + DOMAIN_QUERY_TEMPLATE, Long.class)
              .setParameter("registrarId", registrarId)
              .setParameter("endOfTime", DateTimeUtils.END_OF_TIME)
              .setParameter("createdBeforeTime", createdBeforeTime)
              .getSingleResult();
    } else {
      totalResults = domainListRequest.totalResults;
    }
    List<Domain> domains =
        tm().query(DOMAIN_QUERY_TEMPLATE + " ORDER BY creationTime DESC", Domain.class)
            .setParameter("registrarId", registrarId)
            .setParameter("endOfTime", DateTimeUtils.END_OF_TIME)
            .setParameter("createdBeforeTime", createdBeforeTime)
            .setFirstResult(numResultsToSkip)
            .setMaxResults(domainListRequest.resultsPerPage)
            .getResultList();
    response.setPayload(
        gson.toJson(new DomainListResult(domains, createdBeforeTime.getTimestamp(), totalResults)));
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }

  private void writeBadRequest(String message) {
    response.setPayload(message);
    response.setStatus(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
  }

  /** Template for the input we expect to receive from the domain-list frontend. */
  public static final class DomainListRequest {
    @Expose @Nullable DateTime createdBeforeTime;
    @Expose int pageNumber = 0;
    @Expose int resultsPerPage = DEFAULT_RESULTS_PER_PAGE;
    @Expose @Nullable Long totalResults;

    // for GSON
    private DomainListRequest() {}

    @VisibleForTesting
    DomainListRequest(
        @Nullable DateTime createdBeforeTime,
        int pageNumber,
        int resultsPerPage,
        @Nullable Long totalResults) {
      this.createdBeforeTime = createdBeforeTime;
      this.pageNumber = pageNumber;
      this.resultsPerPage = resultsPerPage;
      this.totalResults = totalResults;
    }
  }

  /** Container result class that allows for pagination. */
  @VisibleForTesting
  static final class DomainListResult {
    @Expose List<Domain> domains;
    @Expose DateTime createdBeforeTime;
    @Expose long totalResults;

    private DomainListResult(List<Domain> domains, DateTime createdBeforeTime, long totalResults) {
      this.domains = domains;
      this.createdBeforeTime = createdBeforeTime;
      this.totalResults = totalResults;
    }
  }
}
