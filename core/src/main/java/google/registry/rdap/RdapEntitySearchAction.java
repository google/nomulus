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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.rdap.RdapUtils.getRegistrarByIanaIdentifier;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.primitives.Booleans;
import com.google.common.primitives.Longs;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapSearchResults.EntitySearchResponse;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.HttpException.UnprocessableEntityException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * RDAP action for entity (i.e. registrar) search requests.
 *
 * <p>All commands and responses conform to the RDAP spec as defined in STD 95 and its RFCs.
 *
 * <p>There are two ways to search for entities: by full name (the registrar name) or by handle (the
 * IANA number). The ICANN operational profile document specifies this meaning for handle searches.
 *
 * @see <a href="http://tools.ietf.org/html/rfc9082">RFC 9082: Registration Data Access Protocol
 *     (RDAP) Query Format</a>
 * @see <a href="http://tools.ietf.org/html/rfc9083">RFC 9083: JSON Responses for the Registration
 *     Data Access Protocol (RDAP)</a>
 */
@Action(
    service = GaeService.PUBAPI,
    path = "/rdap/entities",
    method = {GET, HEAD},
    auth = Auth.AUTH_PUBLIC)
public class RdapEntitySearchAction extends RdapSearchActionBase {

  @Inject @Parameter("fn") Optional<String> fnParam;
  @Inject @Parameter("handle") Optional<String> handleParam;
  @Inject public RdapEntitySearchAction() {
    super("entity search", EndpointType.ENTITIES);
  }

  /** Parses the parameters and calls the appropriate search function. */
  @Override
  public EntitySearchResponse getSearchResponse(boolean isHeadRequest) {
    // RDAP syntax example: /rdap/entities?fn=Bobby%20Joe*.
    if (Booleans.countTrue(fnParam.isPresent(), handleParam.isPresent()) != 1) {
      throw new BadRequestException("You must specify either fn=XXXX or handle=YYYY");
    }

    // Search by name.
    EntitySearchResponse results;
    if (fnParam.isPresent()) {
      metricInformationBuilder.setSearchType(SearchType.BY_FULL_NAME);
      // syntax: /rdap/entities?fn=Bobby%20Joe*
      // The name is the registrar name (not registrar contact name).
      results =
          searchByName(
              recordWildcardType(RdapSearchPattern.createFromUnicodeString(fnParam.get())));
    } else {
      // Search by handle.
      metricInformationBuilder.setSearchType(SearchType.BY_HANDLE);
      // syntax: /rdap/entities?handle=12345-*
      // The handle is the registrar ID.
      results =
          searchByHandle(
              recordWildcardType(RdapSearchPattern.createFromUnicodeString(handleParam.get())));
    }

    // Build the result object and return it.
    if (results.entitySearchResults().isEmpty()) {
      throw new NotFoundException("No entities found");
    }
    return results;
  }

  /**
   * Searches for entities by name, returning a JSON array of entity info maps.
   *
   * <p>As per Gustavo Lozano of ICANN, registrar name search should be by registrar name only, not
   * by registrar contact name:
   *
   * <p>The search is by registrar name only. The profile is supporting the functionality defined in
   * the Base Registry Agreement.
   *
   * <p>According to RFC 9082 section 6.1, punycode is only used for domain name labels, so we can
   * assume that entity names are regular unicode.
   *
   * @see <a
   *     href="https://newgtlds.icann.org/sites/default/files/agreements/agreement-approved-09jan14-en.htm">1.6
   *     of Section 4 of the Base Registry Agreement</a>
   */
  private EntitySearchResponse searchByName(final RdapSearchPattern partialStringQuery) {
    // Don't allow wildcard suffixes when searching for entities.
    if (partialStringQuery.getHasWildcard() && (partialStringQuery.getSuffix() != null)) {
      throw new UnprocessableEntityException(
          "Suffixes not allowed in wildcard entity name searches");
    }
    // Get the registrar matches. If we have a cursor, weed out registrars up to and including the
    // one we ended with last time.
    ImmutableList<Registrar> registrars =
        Streams.stream(Registrar.loadAllCached())
            .sorted(
                Comparator.comparing(Registrar::getRegistrarName, String.CASE_INSENSITIVE_ORDER))
            .filter(
                registrar ->
                    partialStringQuery.matches(registrar.getRegistrarName())
                        && (cursorString.isEmpty()
                            || (registrar.getRegistrarName().compareTo(cursorString.get()) > 0))
                        && shouldBeVisible(registrar))
            .limit(rdapResultSetMaxSize + 1)
            .collect(toImmutableList());

    return makeSearchResults(registrars);
  }

  /**
   * Searches for entities by handle, returning a JSON array of entity info maps.
   *
   * <p>Searches for deleted entities are treated like wildcard searches.
   *
   * <p>We don't allow suffixes after a wildcard in entity searches. Suffixes are used in domain
   * searches to specify a TLD, and in nameserver searches to specify a locally managed domain name.
   * In both cases, the suffix can be turned into an additional query filter field.
   */
  private EntitySearchResponse searchByHandle(final RdapSearchPattern partialStringQuery) {
    if (partialStringQuery.getSuffix() != null) {
      throw new UnprocessableEntityException("Suffixes not allowed in entity handle searches");
    }
    // Handle queries without a wildcard (and not including deleted) -- load by ID.
    if (!partialStringQuery.getHasWildcard() && !shouldIncludeDeleted()) {
      return makeSearchResults(getMatchingRegistrars(partialStringQuery.getInitialString()));
    }
    // Handle queries with a wildcard (or including deleted), but no suffix. Because the handle
    // for registrars is the IANA identifier number, don't allow wildcard searches for registrars,
    // by simply not searching for registrars if a wildcard is present (unless the request is for
    // all registrars, in which case we know what to do).
    ImmutableList<Registrar> registrars;
    if (partialStringQuery.getHasWildcard() && partialStringQuery.getInitialString().isEmpty()) {
      // Even though we are searching by IANA identifier, we should still sort by name, because
      // the IANA identifier can by missing, and sorting on that would screw up our cursors.
      registrars =
          Streams.stream(Registrar.loadAllCached())
              .sorted(
                  Comparator.comparing(Registrar::getRegistrarName, String.CASE_INSENSITIVE_ORDER))
              .filter(
                  registrar ->
                      (cursorString.isEmpty()
                              || (registrar.getRegistrarName().compareTo(cursorString.get()) > 0))
                          && shouldBeVisible(registrar))
              .limit(rdapResultSetMaxSize + 1)
              .collect(toImmutableList());
    } else if (partialStringQuery.getHasWildcard()) {
      registrars = ImmutableList.of();
    } else {
      registrars = getMatchingRegistrars(partialStringQuery.getInitialString());
    }
    return makeSearchResults(registrars);
  }

  /** Looks up registrars by handle (i.e. IANA identifier). */
  private ImmutableList<Registrar> getMatchingRegistrars(final String ianaIdentifierString) {
    Long ianaIdentifier = Longs.tryParse(ianaIdentifierString);
    if (ianaIdentifier == null) {
      return ImmutableList.of();
    }
    Optional<Registrar> registrar = getRegistrarByIanaIdentifier(ianaIdentifier);
    return (registrar.isPresent() && shouldBeVisible(registrar.get()))
        ? ImmutableList.of(registrar.get())
        : ImmutableList.of();
  }

  /** Builds a JSON array of entity info maps based on the specified registrars. */
  private EntitySearchResponse makeSearchResults(List<Registrar> registrars) {
    // Determine what output data type to use, depending on whether more than one entity will be
    // returned.
    OutputDataType outputDataType =
        registrars.size() > 1 ? OutputDataType.SUMMARY : OutputDataType.FULL;
    EntitySearchResponse.Builder builder = EntitySearchResponse.builder();
    Iterable<Registrar> limitedRegistrars = Iterables.limit(registrars, rdapResultSetMaxSize);
    for (Registrar registrar : limitedRegistrars) {
      builder
          .entitySearchResultsBuilder()
          .add(rdapJsonFormatter.createRdapRegistrarEntity(registrar, outputDataType));
    }
    if (rdapResultSetMaxSize < registrars.size()) {
      builder.setNextPageUri(
          createNavigationUri(Iterables.getLast(limitedRegistrars).getRegistrarName()));
      builder.setIncompletenessWarningType(IncompletenessWarningType.TRUNCATED);
    } else {
      builder.setIncompletenessWarningType(IncompletenessWarningType.COMPLETE);
    }
    return builder.build();
  }
}
