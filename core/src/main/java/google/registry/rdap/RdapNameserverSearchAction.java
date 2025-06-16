// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static google.registry.model.EppResourceUtils.loadByForeignKeyByCache;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.Booleans;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.rdap.RdapSearchResults.NameserverSearchResponse;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.HttpException.UnprocessableEntityException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RDAP (new WHOIS) action for nameserver search requests.
 *
 * <p>All commands and responses conform to the RDAP spec as defined in RFCs 7480 through 7485.
 *
 * @see <a href="http://tools.ietf.org/html/rfc9082">RFC 9082: Registration Data Access Protocol
 *     (RDAP) Query Format</a>
 * @see <a href="http://tools.ietf.org/html/rfc9083">RFC 9083: JSON Responses for the Registration
 *     Data Access Protocol (RDAP)</a>
 */
@Action(
    service = GaeService.PUBAPI,
    path = "/rdap/nameservers",
    method = {GET, HEAD},
    auth = Auth.AUTH_PUBLIC)
public class RdapNameserverSearchAction extends RdapSearchActionBase {

  public static final String PATH = "/rdap/nameservers";

  @Inject @Parameter("name") Optional<String> nameParam;
  @Inject @Parameter("ip") Optional<String> ipParam;
  @Inject public RdapNameserverSearchAction() {
    super("nameserver search", EndpointType.NAMESERVERS);
  }

  private enum CursorType {
    NAME,
    ADDRESS
  }

  /**
   * Parses the parameters and calls the appropriate search function.
   *
   * <p>The RDAP spec allows nameserver search by either name or IP address.
   */
  @Override
  public NameserverSearchResponse getSearchResponse(boolean isHeadRequest) {
    // RDAP syntax example: /rdap/nameservers?name=ns*.example.com.
    if (Booleans.countTrue(nameParam.isPresent(), ipParam.isPresent()) != 1) {
      throw new BadRequestException("You must specify either name=XXXX or ip=YYYY");
    }
    NameserverSearchResponse results;
    if (nameParam.isPresent()) {
      // RDAP Technical Implementation Guide 2.2.3 - we MAY support nameserver search queries based
      // on a "nameserver search pattern" as defined in RFC 9082
      //
      // syntax: /rdap/nameservers?name=exam*.com
      metricInformationBuilder.setSearchType(SearchType.BY_NAMESERVER_NAME);
      results =
          searchByName(
              recordWildcardType(
                  RdapSearchPattern.createFromLdhOrUnicodeDomainName(nameParam.get())));
    } else {
      // RDAP Technical Implementation Guide 2.2.3 - we MUST support nameserver search queries based
      // on IP address as defined in RFC 9082 3.2.2. Doesn't require pattern matching
      //
      // syntax: /rdap/nameservers?ip=1.2.3.4
      metricInformationBuilder.setSearchType(SearchType.BY_NAMESERVER_ADDRESS);
      InetAddress inetAddress;
      try {
        inetAddress = InetAddresses.forString(ipParam.get());
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Invalid value of ip parameter");
      }
      results = searchByIp(inetAddress);
    }
    if (results.nameserverSearchResults().isEmpty()) {
      throw new NotFoundException("No nameservers found");
    }
    return results;
  }

  /**
   * Searches for nameservers by name, returning a JSON array of nameserver info maps.
   *
   * <p>When deleted nameservers are included in the search, the search is treated as if it has a
   * wildcard, because multiple results can be returned.
   */
  private NameserverSearchResponse searchByName(final RdapSearchPattern partialStringQuery) {
    // Handle queries without a wildcard -- just load by foreign key. We can't do this if deleted
    // nameservers are desired, because there may be multiple nameservers with the same name.
    if (!partialStringQuery.getHasWildcard() && !shouldIncludeDeleted()) {
      return searchByNameUsingForeignKey(partialStringQuery);
    }
    // Handle queries with a wildcard (or including deleted entries). If there is a suffix, it
    // should be a domain that we manage, so we can look up the domain and search through the
    // subordinate hosts. This is more efficient, and lets us permit wildcard searches with no
    // initial string. Deleted nameservers cannot be searched using a suffix, because the logic
    // of the deletion status of the superordinate domain versus the deletion status of the
    // subordinate host gets too messy.
    if (partialStringQuery.getSuffix() != null) {
      if (shouldIncludeDeleted()) {
        throw new UnprocessableEntityException(
            "A suffix after a wildcard is not allowed when searching for deleted nameservers");
      }
      return searchByNameUsingSuperordinateDomain(partialStringQuery);
    }
    // Handle queries with a wildcard (or deleted entries included), but no suffix.
    return searchByNameUsingPrefix(partialStringQuery);
  }

  /**
   * Searches for nameservers by name with no wildcard or deleted names.
   *
   * <p>In this case, we can load by foreign key.
   */
  private NameserverSearchResponse searchByNameUsingForeignKey(
      RdapSearchPattern partialStringQuery) {
    NameserverSearchResponse.Builder builder =
        NameserverSearchResponse.builder()
            .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE);

    Optional<Host> host =
        loadByForeignKeyByCache(
            Host.class, partialStringQuery.getInitialString(), getRequestTime());

    metricInformationBuilder.setNumHostsRetrieved(host.isPresent() ? 1 : 0);

    if (shouldBeVisible(host)) {
      builder
          .nameserverSearchResultsBuilder()
          .add(rdapJsonFormatter.createRdapNameserver(host.get(), OutputDataType.FULL));
    }
    return builder.build();
  }

  /** Searches for nameservers by name using the superordinate domain as a suffix. */
  private NameserverSearchResponse searchByNameUsingSuperordinateDomain(
      RdapSearchPattern partialStringQuery) {
    Optional<Domain> domain =
        loadByForeignKeyByCache(Domain.class, partialStringQuery.getSuffix(), getRequestTime());
    if (domain.isEmpty()) {
      // Don't allow wildcards with suffixes which are not domains we manage. That would risk a
      // table scan in many easily foreseeable cases. The user might ask for ns*.zombo.com,
      // forcing us to query for all hosts beginning with ns, then filter for those ending in
      // .zombo.com. It might well be that 80% of all hostnames begin with ns, leading to
      // inefficiency.
      throw new UnprocessableEntityException(
          "A suffix after a wildcard in a nameserver lookup must be an in-bailiwick domain");
    }
    List<Host> hostList = new ArrayList<>();
    for (String fqhn : ImmutableSortedSet.copyOf(domain.get().getSubordinateHosts())) {
      if (cursorString.isPresent() && (fqhn.compareTo(cursorString.get()) <= 0)) {
        continue;
      }
      // We can't just check that the host name starts with the initial query string, because
      // then the query ns.exam*.example.com would match against nameserver ns.example.com.
      if (partialStringQuery.matches(fqhn)) {
        Optional<Host> host = loadByForeignKeyByCache(Host.class, fqhn, getRequestTime());
        if (shouldBeVisible(host)) {
          hostList.add(host.get());
          if (hostList.size() > rdapResultSetMaxSize) {
            break;
          }
        }
      }
    }
    return makeSearchResults(
        hostList,
        IncompletenessWarningType.COMPLETE,
        domain.get().getSubordinateHosts().size(),
        CursorType.NAME);
  }

  /**
   * Searches for nameservers by name with a prefix and wildcard.
   *
   * <p>There are no pending deletes for hosts, so we can call {@link
   * RdapSearchActionBase#queryItems}.
   */
  private NameserverSearchResponse searchByNameUsingPrefix(RdapSearchPattern partialStringQuery) {
    // Add 1 so we can detect truncation.
    int querySizeLimit = getStandardQuerySizeLimit();
    return replicaTm()
        .transact(
            () -> {
              CriteriaQueryBuilder<Host> queryBuilder =
                  queryItems(
                      Host.class,
                      "hostName",
                      partialStringQuery,
                      cursorString,
                      getDeletedItemHandling());
              return makeSearchResults(
                  getMatchingResources(queryBuilder, shouldIncludeDeleted(), querySizeLimit),
                  CursorType.NAME);
            });
  }

  /** Searches for nameservers by IP address, returning a JSON array of nameserver info maps. */
  private NameserverSearchResponse searchByIp(InetAddress inetAddress) {
    // Add 1 so we can detect truncation.
    int querySizeLimit = getStandardQuerySizeLimit();
    RdapResultSet<Host> rdapResultSet;
      // Hibernate does not allow us to query @Converted array fields directly, either in the
      // CriteriaQuery or the raw text format. However, Postgres does -- so we use native queries to
      // find hosts where any of the inetAddresses match.
      StringBuilder queryBuilder =
          new StringBuilder("SELECT * FROM \"Host\" WHERE :address = ANY(inet_addresses)");
      ImmutableMap.Builder<String, String> parameters =
          new ImmutableMap.Builder<String, String>()
              .put("address", InetAddresses.toAddrString(inetAddress));
      if (getDeletedItemHandling().equals(DeletedItemHandling.EXCLUDE)) {
        queryBuilder.append(" AND deletion_time = CAST(:endOfTime AS timestamptz)");
        parameters.put("endOfTime", END_OF_TIME.toString());
      }
      if (cursorString.isPresent()) {
        // cursorString here must be the repo ID
        queryBuilder.append(" AND repo_id > :repoId");
        parameters.put("repoId", cursorString.get());
      }
      if (getDesiredRegistrar().isPresent()) {
        queryBuilder.append(" AND current_sponsor_registrar_id = :desiredRegistrar");
        parameters.put("desiredRegistrar", getDesiredRegistrar().get());
      }
      queryBuilder.append(" ORDER BY repo_id ASC");
    rdapResultSet =
        replicaTm()
            .transact(
                () -> {
                  jakarta.persistence.Query query =
                      replicaTm()
                          .getEntityManager()
                          .createNativeQuery(queryBuilder.toString(), Host.class)
                          .setMaxResults(querySizeLimit);
                  parameters.build().forEach(query::setParameter);
                  @SuppressWarnings("unchecked")
                  List<Host> resultList = query.getResultList();
                  return filterResourcesByVisibility(resultList, querySizeLimit);
                });
    return makeSearchResults(rdapResultSet, CursorType.ADDRESS);
  }

  /** Output JSON for a lists of hosts contained in an {@link RdapResultSet}. */
  private NameserverSearchResponse makeSearchResults(
      RdapResultSet<Host> resultSet, CursorType cursorType) {
    return makeSearchResults(
        resultSet.resources(),
        resultSet.incompletenessWarningType(),
        resultSet.numResourcesRetrieved(),
        cursorType);
  }

  /** Output JSON for a list of hosts. */
  private NameserverSearchResponse makeSearchResults(
      List<Host> hosts,
      IncompletenessWarningType incompletenessWarningType,
      int numHostsRetrieved,
      CursorType cursorType) {
    metricInformationBuilder.setNumHostsRetrieved(numHostsRetrieved);
    OutputDataType outputDataType =
        (hosts.size() > 1) ? OutputDataType.SUMMARY : OutputDataType.FULL;
    NameserverSearchResponse.Builder builder =
        NameserverSearchResponse.builder().setIncompletenessWarningType(incompletenessWarningType);
    Optional<String> newCursor = Optional.empty();
    for (Host host : Iterables.limit(hosts, rdapResultSetMaxSize)) {
      newCursor =
          Optional.of((cursorType == CursorType.NAME) ? host.getHostName() : host.getRepoId());
      builder
          .nameserverSearchResultsBuilder()
          .add(rdapJsonFormatter.createRdapNameserver(host, outputDataType));
    }
    if (rdapResultSetMaxSize < hosts.size()) {
      builder.setNextPageUri(createNavigationUri(newCursor.get()));
      builder.setIncompletenessWarningType(IncompletenessWarningType.TRUNCATED);
    }
    return builder.build();
  }
}
