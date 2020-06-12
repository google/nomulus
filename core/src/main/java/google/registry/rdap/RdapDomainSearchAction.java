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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.index.ForeignKeyIndex.loadAndGetKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.Booleans;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.DomainSearchResponse;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.HttpException.UnprocessableEntityException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.NonFinalForTesting;
import java.net.InetAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.inject.Inject;

/**
 * RDAP (new WHOIS) action for domain search requests.
 *
 * <p>All commands and responses conform to the RDAP spec as defined in RFCs 7480 through 7485.
 *
 * @see <a href="http://tools.ietf.org/html/rfc7482">RFC 7482: Registration Data Access Protocol
 *     (RDAP) Query Format</a>
 * @see <a href="http://tools.ietf.org/html/rfc7483">RFC 7483: JSON Responses for the Registration
 *     Data Access Protocol (RDAP)</a>
 */
// TODO: This isn't required by the RDAP Technical Implementation Guide, and hence should be
// deleted, at least until it's actually required.
@Action(
    service = Action.Service.PUBAPI,
    path = "/rdap/domains",
    method = {GET, HEAD},
    auth = Auth.AUTH_PUBLIC)
public class RdapDomainSearchAction extends RdapSearchActionBase {

  static final int RESULT_SET_SIZE_SCALING_FACTOR = 30;

  @NonFinalForTesting
  static int maxNameserversInFirstStage = 300;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject @Parameter("name") Optional<String> nameParam;
  @Inject @Parameter("nsLdhName") Optional<String> nsLdhNameParam;
  @Inject @Parameter("nsIp") Optional<String> nsIpParam;
  @Inject public RdapDomainSearchAction() {
    super("domain search", EndpointType.DOMAINS);
  }

  /**
   * Parses the parameters and calls the appropriate search function.
   *
   * <p>The RDAP spec allows for domain search by domain name, nameserver name or nameserver IP.
   */
  @Override
  public DomainSearchResponse getSearchResponse(boolean isHeadRequest) {
    // RDAP syntax example: /rdap/domains?name=exam*.com.
    if (Booleans.countTrue(nameParam.isPresent(), nsLdhNameParam.isPresent(), nsIpParam.isPresent())
        != 1) {
      throw new BadRequestException(
          "You must specify either name=XXXX, nsLdhName=YYYY or nsIp=ZZZZ");
    }
    DomainSearchResponse results;
    if (nameParam.isPresent()) {
      metricInformationBuilder.setSearchType(SearchType.BY_DOMAIN_NAME);
      // syntax: /rdap/domains?name=exam*.com
      results =
          searchByDomainName(
              recordWildcardType(
                  RdapSearchPattern.createFromLdhOrUnicodeDomainName(nameParam.get())));
    } else if (nsLdhNameParam.isPresent()) {
      metricInformationBuilder.setSearchType(SearchType.BY_NAMESERVER_NAME);
      // syntax: /rdap/domains?nsLdhName=ns1.exam*.com
      // RFC 7482 appears to say that Unicode domains must be specified using punycode when
      // passed to nsLdhName, so IDN.toASCII is not called here.
      results =
          searchByNameserverLdhName(
              recordWildcardType(RdapSearchPattern.createFromLdhDomainName(nsLdhNameParam.get())));
    } else {
      metricInformationBuilder.setSearchType(SearchType.BY_NAMESERVER_ADDRESS);
      metricInformationBuilder.setWildcardType(WildcardType.NO_WILDCARD);
      metricInformationBuilder.setPrefixLength(nsIpParam.get().length());
      // syntax: /rdap/domains?nsIp=1.2.3.4
      InetAddress inetAddress;
      try {
        inetAddress = InetAddresses.forString(nsIpParam.get());
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Invalid value of nsIp parameter");
      }
      results = searchByNameserverIp(inetAddress);
    }
    if (results.domainSearchResults().isEmpty()) {
      throw new NotFoundException("No domains found");
    }
    return results;
  }

  /**
   * Searches for domains by domain name, returning a JSON array of domain info maps.
   *
   * <p>Domain query strings with wildcards are allowed to have a suffix after the wildcard, which
   * must be a TLD. If the TLD is not present, the wildcard must be preceded by at least two
   * characters (e.g. "ex*"), to avoid queries for all domains in the system. If the TLD is present,
   * the initial string is not required (e.g. "*.tld" is valid), because the search will be
   * restricted to a single TLD.
   *
   * <p>Searches which include deleted entries are effectively treated as if they have a wildcard,
   * since the same name can return multiple results.
   */
  private DomainSearchResponse searchByDomainName(final RdapSearchPattern partialStringQuery) {
    // Handle queries without a wildcard -- just load by foreign key. We can't do this if deleted
    // entries are included, because there may be multiple nameservers with the same name.
    if (!partialStringQuery.getHasWildcard() && !shouldIncludeDeleted()) {
      return searchByDomainNameWithoutWildcard(partialStringQuery);
    }
    // Handle queries with a wildcard and initial search string. We require either a TLD or an
    // initial string at least MIN_INITIAL_STRING_LENGTH long.
    if (!partialStringQuery.getInitialString().isEmpty()) {
      if ((partialStringQuery.getSuffix() == null)
          && (partialStringQuery.getInitialString().length()
              < RdapSearchPattern.MIN_INITIAL_STRING_LENGTH)) {
        throw new UnprocessableEntityException(
            String.format(
                "Initial search string must be at least %d characters for wildcard domain searches"
                    + " without a TLD suffix",
                RdapSearchPattern.MIN_INITIAL_STRING_LENGTH));
      }
      return searchByDomainNameWithInitialString(partialStringQuery);
    }
    if (partialStringQuery.getSuffix() == null) {
      throw new UnprocessableEntityException(
          "Initial search string is required for wildcard domain searches without a TLD suffix");
    }
    return searchByDomainNameByTld(partialStringQuery.getSuffix());
  }

  /** Searches for domains by domain name without a wildcard or interest in deleted entries. */
  private DomainSearchResponse searchByDomainNameWithoutWildcard(
      final RdapSearchPattern partialStringQuery) {
    Optional<DomainBase> domainBase =
        loadByForeignKey(DomainBase.class, partialStringQuery.getInitialString(), getRequestTime());
    return makeSearchResults(
        shouldBeVisible(domainBase)
            ? ImmutableList.of(domainBase.get())
            : ImmutableList.of());
  }

  /** Searches for domains by domain name with an initial string, wildcard and possible suffix. */
  private DomainSearchResponse searchByDomainNameWithInitialString(
      final RdapSearchPattern partialStringQuery) {
    // We can't query for undeleted domains as part of the query itself; that would require an
    // inequality query on deletion time, and we are already using inequality queries on
    // fullyQualifiedDomainName. So we instead pick an arbitrary limit of
    // RESULT_SET_SIZE_SCALING_FACTOR times the result set size limit, fetch up to that many, and
    // weed out all deleted domains. If there still isn't a full result set's worth of domains, we
    // give up and return just the ones we found. Don't use queryItems, because it checks that the
    // initial string is at least a certain length, which we don't need in this case. Query the
    // domains directly, rather than the foreign keys, because then we have an index on TLD if we
    // need it.
    int querySizeLimit = RESULT_SET_SIZE_SCALING_FACTOR * rdapResultSetMaxSize;
    Query<DomainBase> query =
        ofy()
            .load()
            .type(DomainBase.class)
            .filter("fullyQualifiedDomainName <", partialStringQuery.getNextInitialString())
            .filter("fullyQualifiedDomainName >=", partialStringQuery.getInitialString());
    if (cursorString.isPresent()) {
      query = query.filter("fullyQualifiedDomainName >", cursorString.get());
    }
    if (partialStringQuery.getSuffix() != null) {
      query = query.filter("tld", partialStringQuery.getSuffix());
    }
    query = query.limit(querySizeLimit);
    // Always check for visibility, because we couldn't look at the deletionTime in the query.
    return makeSearchResults(getMatchingResources(query, true, querySizeLimit));
  }

  /** Searches for domains by domain name with a TLD suffix. */
  private DomainSearchResponse searchByDomainNameByTld(String tld) {
    // Even though we are not searching on fullyQualifiedDomainName, we want the results to come
    // back ordered by name, so we are still in the same boat as
    // searchByDomainNameWithInitialString, unable to perform an inequality query on deletion time.
    // Don't use queryItems, because it doesn't handle pending deletes.
    int querySizeLimit = RESULT_SET_SIZE_SCALING_FACTOR * rdapResultSetMaxSize;
    Query<DomainBase> query =
        ofy()
            .load()
            .type(DomainBase.class)
            .filter("tld", tld);
    if (cursorString.isPresent()) {
      query = query.filter("fullyQualifiedDomainName >", cursorString.get());
    }
    query = query.order("fullyQualifiedDomainName").limit(querySizeLimit);
    return makeSearchResults(getMatchingResources(query, true, querySizeLimit));
  }

  /**
   * Searches for domains by nameserver name, returning a JSON array of domain info maps.
   *
   * <p>This is a two-step process: get a list of host references by host name, and then look up
   * domains by host reference.
   *
   * <p>The includeDeleted parameter does NOT cause deleted nameservers to be searched, only deleted
   * domains which used to be connected to an undeleted nameserver.
   */
  private DomainSearchResponse searchByNameserverLdhName(
      final RdapSearchPattern partialStringQuery) {
    Iterable<VKey<HostResource>> hostKeys = getNameserverRefsByLdhName(partialStringQuery);
    if (Iterables.isEmpty(hostKeys)) {
      metricInformationBuilder.setNumHostsRetrieved(0);
      throw new NotFoundException("No matching nameservers found");
    }
    return searchByNameserverRefs(hostKeys);
  }

  /**
   * Assembles a list of {@link HostResource} keys by name.
   *
   * <p>Nameserver query strings with wildcards are allowed to have a suffix after the wildcard,
   * which must be a domain. If the domain is not specified, or is not an existing domain in one of
   * our TLDs, the wildcard must be preceded by at least two characters (e.g. "ns*"), to avoid
   * queries for all nameservers in the system. If the suffix specifies an existing domain, the
   * initial string is not required (e.g. "*.example.tld" is valid), because we can look up the
   * domain and just list all of its subordinate hosts.
   */
  private Iterable<VKey<HostResource>> getNameserverRefsByLdhName(
      final RdapSearchPattern partialStringQuery) {
    // Handle queries without a wildcard.
    if (!partialStringQuery.getHasWildcard()) {
      return getNameserverRefsByLdhNameWithoutWildcard(partialStringQuery);
    }
    // Handle queries with a wildcard and suffix (specifying a suprerordinate domain).
    if (partialStringQuery.getSuffix() != null) {
      return getNameserverRefsByLdhNameWithSuffix(partialStringQuery);
    }
    // If there's no suffix, query the host resources. Query the resources themselves, rather than
    // the foreign key indexes, because then we have an index on fully qualified host name and
    // deletion time, so we can check the deletion status in the query itself. The initial string
    // must be present, to avoid querying every host in the system. This restriction is enforced by
    // {@link queryItems}.
    //
    // Only return the first maxNameserversInFirstStage nameservers. This could result in an
    // incomplete result set if a search asks for something like "ns*", but we need to enforce a
    // limit in order to avoid arbitrarily long-running queries.
    Query<HostResource> query =
        queryItems(
            HostResource.class,
            "fullyQualifiedHostName",
            partialStringQuery,
            Optional.empty(),
            DeletedItemHandling.EXCLUDE,
            maxNameserversInFirstStage);
    Optional<String> desiredRegistrar = getDesiredRegistrar();
    if (desiredRegistrar.isPresent()) {
      query = query.filter("currentSponsorClientId", desiredRegistrar.get());
    }
    return StreamSupport.stream(query.keys().spliterator(), false)
        .map(key -> HostResource.createVKey(key))
        .collect(toImmutableSet());
  }

  /** Assembles a list of {@link HostResource} keys by name when the pattern has no wildcard. */
  private Iterable<VKey<HostResource>> getNameserverRefsByLdhNameWithoutWildcard(
      final RdapSearchPattern partialStringQuery) {
    // If we need to check the sponsoring registrar, we need to load the resource rather than just
    // the key.
    Optional<String> desiredRegistrar = getDesiredRegistrar();
    if (desiredRegistrar.isPresent()) {
      Optional<HostResource> host =
          loadByForeignKey(
              HostResource.class,
              partialStringQuery.getInitialString(),
              shouldIncludeDeleted() ? START_OF_TIME : getRequestTime());
      return (!host.isPresent()
              || !desiredRegistrar.get().equals(host.get().getPersistedCurrentSponsorClientId()))
          ? ImmutableList.of()
          : ImmutableList.of(host.get().createVKey());
    } else {
      VKey<HostResource> hostKey =
          loadAndGetKey(
              HostResource.class,
              partialStringQuery.getInitialString(),
              shouldIncludeDeleted() ? START_OF_TIME : getRequestTime());
      return (hostKey == null) ? ImmutableList.of() : ImmutableList.of(hostKey);
    }
  }

  /** Assembles a list of {@link HostResource} keys by name using a superordinate domain suffix. */
  private Iterable<VKey<HostResource>> getNameserverRefsByLdhNameWithSuffix(
      final RdapSearchPattern partialStringQuery) {
    // The suffix must be a domain that we manage. That way, we can look up the domain and search
    // through the subordinate hosts. This is more efficient, and lets us permit wildcard searches
    // with no initial string.
    DomainBase domainBase =
        loadByForeignKey(
                DomainBase.class,
                partialStringQuery.getSuffix(),
                shouldIncludeDeleted() ? START_OF_TIME : getRequestTime())
            .orElseThrow(
                () ->
                    new UnprocessableEntityException(
                        "A suffix in a lookup by nameserver name "
                            + "must be a domain defined in the system"));
    Optional<String> desiredRegistrar = getDesiredRegistrar();
    ImmutableList.Builder<VKey<HostResource>> builder = new ImmutableList.Builder<>();
    for (String fqhn : ImmutableSortedSet.copyOf(domainBase.getSubordinateHosts())) {
      // We can't just check that the host name starts with the initial query string, because
      // then the query ns.exam*.example.com would match against nameserver ns.example.com.
      if (partialStringQuery.matches(fqhn)) {
        if (desiredRegistrar.isPresent()) {
          Optional<HostResource> host =
              loadByForeignKey(
                  HostResource.class,
                  fqhn,
                  shouldIncludeDeleted() ? START_OF_TIME : getRequestTime());
          if (host.isPresent()
              && desiredRegistrar.get().equals(host.get().getPersistedCurrentSponsorClientId())) {
            builder.add(host.get().createVKey());
          }
        } else {
          VKey<HostResource> hostKey =
              loadAndGetKey(
                  HostResource.class,
                  fqhn,
                  shouldIncludeDeleted() ? START_OF_TIME : getRequestTime());
          if (hostKey != null) {
            builder.add(hostKey);
          } else {
            logger.atWarning().log("Host key unexpectedly null");
          }
        }
      }
    }
    return builder.build();
  }

  /**
   * Searches for domains by nameserver address, returning a JSON array of domain info maps.
   *
   * <p>This is a two-step process: get a list of host references by IP address, and then look up
   * domains by host reference.
   *
   * <p>In theory, we could have any number of hosts using the same IP address. To make sure we get
   * all the associated domains, we have to retrieve all of them, and use them to look up domains.
   * This could open us up to a kind of DoS attack if huge number of hosts are defined on a single
   * IP. To avoid this, fetch only the first {@link #maxNameserversInFirstStage} nameservers. In all
   * normal circumstances, this should be orders of magnitude more than there actually are. But it
   * could result in us missing some domains.
   *
   * <p>The includeDeleted parameter does NOT cause deleted nameservers to be searched, only deleted
   * domains which used to be connected to an undeleted nameserver.
   */
  private DomainSearchResponse searchByNameserverIp(final InetAddress inetAddress) {
    Query<HostResource> query =
        queryItems(
            HostResource.class,
            "inetAddresses",
            inetAddress.getHostAddress(),
            Optional.empty(),
            Optional.empty(),
            DeletedItemHandling.EXCLUDE,
            maxNameserversInFirstStage);
    Optional<String> desiredRegistrar = getDesiredRegistrar();
    if (desiredRegistrar.isPresent()) {
      query = query.filter("currentSponsorClientId", desiredRegistrar.get());
    }
    return searchByNameserverRefs(
        StreamSupport.stream(query.keys().spliterator(), false)
            .map(key -> HostResource.createVKey(key))
            .collect(toImmutableSet()));
  }

  /**
   * Locates all domains which are linked to a set of host keys.
   *
   * <p>This method is called by {@link #searchByNameserverLdhName} and {@link
   * #searchByNameserverIp} after they assemble the relevant host keys.
   */
  private DomainSearchResponse searchByNameserverRefs(final Iterable<VKey<HostResource>> hostKeys) {
    // We must break the query up into chunks, because the in operator is limited to 30 subqueries.
    // Since it is possible for the same domain to show up more than once in our result list (if
    // we do a wildcard nameserver search that returns multiple nameservers used by the same
    // domain), we must create a set of resulting {@link DomainBase} objects. Use a sorted set,
    // and fetch all domains, to make sure that we can return the first domains in alphabetical
    // order.
    ImmutableSortedSet.Builder<DomainBase> domainSetBuilder =
        ImmutableSortedSet.orderedBy(Comparator.comparing(DomainBase::getDomainName));
    int numHostKeysSearched = 0;
    for (List<VKey<HostResource>> chunk : Iterables.partition(hostKeys, 30)) {
      numHostKeysSearched += chunk.size();
      Query<DomainBase> query =
          ofy()
              .load()
              .type(DomainBase.class)
              .filter("nsHosts in", chunk.stream().map(VKey::getOfyKey).collect(toImmutableSet()));
      if (!shouldIncludeDeleted()) {
        query = query.filter("deletionTime >", getRequestTime());
      // If we are not performing an inequality query, we can filter on the cursor in the query.
      // Otherwise, we will need to filter the results afterward.
      } else if (cursorString.isPresent()) {
        query = query.filter("fullyQualifiedDomainName >", cursorString.get());
      }
      Stream<DomainBase> stream = Streams.stream(query).filter(domain -> isAuthorized(domain));
      if (cursorString.isPresent()) {
        stream =
            stream.filter(domain -> (domain.getDomainName().compareTo(cursorString.get()) > 0));
      }
      stream.forEach(domainSetBuilder::add);
    }
    List<DomainBase> domains = domainSetBuilder.build().asList();
    metricInformationBuilder.setNumHostsRetrieved(numHostKeysSearched);
    // If everything that we found will fit in the result, check whether there might have been
    // more results that got dropped because the first stage limit on number of nameservers. If
    // so, indicate the result might be incomplete.
    return makeSearchResults(
        domains,
        (numHostKeysSearched >= maxNameserversInFirstStage)
            ? IncompletenessWarningType.MIGHT_BE_INCOMPLETE
            : IncompletenessWarningType.COMPLETE,
        (numHostKeysSearched > 0) ? Optional.of((long) domains.size()) : Optional.empty());
  }

  /** Output JSON for a list of domains, with no incompleteness warnings. */
  private DomainSearchResponse makeSearchResults(List<DomainBase> domains) {
    return makeSearchResults(
        domains, IncompletenessWarningType.COMPLETE, Optional.of((long) domains.size()));
  }

  /** Output JSON from data in an {@link RdapResultSet} object. */
  private DomainSearchResponse makeSearchResults(RdapResultSet<DomainBase> resultSet) {
    return makeSearchResults(
        resultSet.resources(),
        resultSet.incompletenessWarningType(),
        Optional.of((long) resultSet.numResourcesRetrieved()));
  }

  /**
   * Output JSON for a list of domains.
   *
   * <p>The incompletenessWarningType should be set to TRUNCATED if the search found more results
   * than are in the list, or MIGHT_BE_INCOMPLETE if a search for domains by nameserver returned the
   * maximum number of nameservers in the first stage query.
   */
  private DomainSearchResponse makeSearchResults(
      List<DomainBase> domains,
      IncompletenessWarningType incompletenessWarningType,
      Optional<Long> numDomainsRetrieved) {
    numDomainsRetrieved.ifPresent(metricInformationBuilder::setNumDomainsRetrieved);
    OutputDataType outputDataType =
        (domains.size() > 1) ? OutputDataType.SUMMARY : OutputDataType.FULL;
    DomainSearchResponse.Builder builder =
        DomainSearchResponse.builder()
            .setIncompletenessWarningType(incompletenessWarningType);
    Optional<String> newCursor = Optional.empty();
    for (DomainBase domain : Iterables.limit(domains, rdapResultSetMaxSize)) {
      newCursor = Optional.of(domain.getDomainName());
      builder
          .domainSearchResultsBuilder()
          .add(rdapJsonFormatter.createRdapDomain(domain, outputDataType));
    }
    if (rdapResultSetMaxSize < domains.size()) {
      builder.setNextPageUri(createNavigationUri(newCursor.get()));
      builder.setIncompletenessWarningType(IncompletenessWarningType.TRUNCATED);
    }
    return builder.build();
  }
}
