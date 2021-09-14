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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.rdap.RdapUtils.getRegistrarByIanaIdentifier;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.primitives.Booleans;
import com.google.common.primitives.Longs;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.contact.ContactResource;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.rdap.RdapAuthorization.Role;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapSearchResults.EntitySearchResponse;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.HttpException.UnprocessableEntityException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;

/**
 * RDAP (new WHOIS) action for entity (contact and registrar) search requests.
 *
 * <p>All commands and responses conform to the RDAP spec as defined in RFCs 7480 through 7485.
 *
 * <p>The RDAP specification lumps contacts and registrars together and calls them "entities", which
 * is confusing for us, because "entity" means something else in Objectify. But here, when we use
 * the term, it means either a contact or registrar. When searching for entities, we always start by
 * returning all matching contacts, and after that all matching registrars.
 *
 * <p>There are two ways to search for entities: by full name (for contacts, the search name, for
 * registrars, the registrar name) or by handle (for contacts, the ROID, for registrars, the IANA
 * number). The ICANN operational profile document specifies this meaning for handle searches.
 *
 * <p>Cursors are complicated by the fact that we are essentially doing two independent searches:
 * one for contacts, and one for registrars. To accommodate this, the cursor has a prefix indicating
 * the type of the last returned item. If the last item was a contact, we return c:{value}, where
 * the value is either the search name or the ROID. If the last item was a registrar, we return
 * r:{value}, where the value is either the registrar name or the IANA number. If we get a c:
 * cursor, we use it to weed out contacts, and fetch all registrars. If we get an r: cursor, we know
 * that we can skip the contact search altogether (because we returned a registrar, and all
 * registrars come after all contacts).
 *
 * @see <a href="http://tools.ietf.org/html/rfc9082">RFC 9082: Registration Data Access Protocol
 *     (RDAP) Query Format</a>
 * @see <a href="http://tools.ietf.org/html/rfc9083">RFC 9083: JSON Responses for the Registration
 *     Data Access Protocol (RDAP)</a>
 */
// TODO: This isn't required by the RDAP Technical Implementation Guide, and hence should be
// deleted, at least until it's actually required.
@Action(
    service = Action.Service.PUBAPI,
    path = "/rdap/entities",
    method = {GET, HEAD},
    auth = Auth.AUTH_PUBLIC)
public class RdapEntitySearchAction extends RdapSearchActionBase {

  @Inject @Parameter("fn") Optional<String> fnParam;
  @Inject @Parameter("handle") Optional<String> handleParam;
  @Inject @Parameter("subtype") Optional<String> subtypeParam;
  @Inject public RdapEntitySearchAction() {
    super("entity search", EndpointType.ENTITIES);
  }

  private enum QueryType {
    FULL_NAME,
    HANDLE
  }

  private enum Subtype {
    ALL,
    CONTACTS,
    REGISTRARS
  }

  private enum CursorType {
    NONE,
    CONTACT,
    REGISTRAR
  }

  private static final String CONTACT_CURSOR_PREFIX = "c:";
  private static final String REGISTRAR_CURSOR_PREFIX = "r:";

  /** Parses the parameters and calls the appropriate search function. */
  @Override
  public EntitySearchResponse getSearchResponse(boolean isHeadRequest) {
    // RDAP syntax example: /rdap/entities?fn=Bobby%20Joe*.
    if (Booleans.countTrue(fnParam.isPresent(), handleParam.isPresent()) != 1) {
      throw new BadRequestException("You must specify either fn=XXXX or handle=YYYY");
    }

    // Check the subtype.
    Subtype subtype;
    if (!subtypeParam.isPresent() || subtypeParam.get().equalsIgnoreCase("all")) {
      subtype = Subtype.ALL;
    } else if (subtypeParam.get().equalsIgnoreCase("contacts")) {
      subtype = Subtype.CONTACTS;
    } else if (subtypeParam.get().equalsIgnoreCase("registrars")) {
      subtype = Subtype.REGISTRARS;
    } else {
      throw new BadRequestException("Subtype parameter must specify contacts, registrars or all");
    }

    CursorType cursorType;
    Optional<String> cursorQueryString;
    if (!cursorString.isPresent()) {
      cursorType = CursorType.NONE;
      cursorQueryString = Optional.empty();
    } else {
      if (cursorString.get().startsWith(CONTACT_CURSOR_PREFIX)) {
        cursorType = CursorType.CONTACT;
        cursorQueryString =
            Optional.of(cursorString.get().substring(CONTACT_CURSOR_PREFIX.length()));
      } else if (cursorString.get().startsWith(REGISTRAR_CURSOR_PREFIX)) {
        cursorType = CursorType.REGISTRAR;
        cursorQueryString =
            Optional.of(cursorString.get().substring(REGISTRAR_CURSOR_PREFIX.length()));
      } else {
        throw new BadRequestException(String.format("invalid cursor: %s", cursorTokenParam));
      }
    }

    // Search by name.
    EntitySearchResponse results;
    if (fnParam.isPresent()) {
      metricInformationBuilder.setSearchType(SearchType.BY_FULL_NAME);
      // syntax: /rdap/entities?fn=Bobby%20Joe*
      // The name is the contact name or registrar name (not registrar contact name).
      results =
          searchByName(
              recordWildcardType(RdapSearchPattern.createFromUnicodeString(fnParam.get())),
              cursorType,
              cursorQueryString,
              subtype);

    // Search by handle.
    } else {
      metricInformationBuilder.setSearchType(SearchType.BY_HANDLE);
      // syntax: /rdap/entities?handle=12345-*
      // The handle is either the contact roid or the registrar clientId.
      results =
          searchByHandle(
              recordWildcardType(RdapSearchPattern.createFromUnicodeString(handleParam.get())),
              cursorType,
              cursorQueryString,
              subtype);
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
   * <p>The includeDeleted flag is ignored when searching for contacts, because contact names are
   * set to null when the contact is deleted, so a deleted contact can never have a name.
   *
   * <p>Since we are restricting access to contact names, we don't want name searches to return
   * contacts whose names are not visible. That would allow unscrupulous users to query by name and
   * infer that all returned contacts contain that name string. So we check the authorization level
   * to determine what to do.
   *
   * @see <a
   *     href="https://newgtlds.icann.org/sites/default/files/agreements/agreement-approved-09jan14-en.htm">1.6
   *     of Section 4 of the Base Registry Agreement</a>
   */
  private EntitySearchResponse searchByName(
      final RdapSearchPattern partialStringQuery,
      CursorType cursorType,
      Optional<String> cursorQueryString,
      Subtype subtype) {
    // Don't allow wildcard suffixes when searching for entities.
    if (partialStringQuery.getHasWildcard() && (partialStringQuery.getSuffix() != null)) {
      throw new UnprocessableEntityException(
          "Suffixes not allowed in wildcard entity name searches");
    }
    // For wildcards, make sure the initial string is long enough, except in the special case of
    // searching for all registrars, where we aren't worried about inefficient searches.
    if (partialStringQuery.getHasWildcard()
        && (subtype != Subtype.REGISTRARS)
        && (partialStringQuery.getInitialString().length()
            < RdapSearchPattern.MIN_INITIAL_STRING_LENGTH)) {
      throw new UnprocessableEntityException(
          "Initial search string required in wildcard entity name searches");
    }
    // Get the registrar matches. If we have a registrar cursor, weed out registrars up to and
    // including the one we ended with last time. We can skip registrars if subtype is CONTACTS.
    ImmutableList<Registrar> registrars;
    if (subtype == Subtype.CONTACTS) {
      registrars = ImmutableList.of();
    } else {
      registrars =
          Streams.stream(Registrar.loadAllCached())
              .sorted(
                  Comparator.comparing(Registrar::getRegistrarName, String.CASE_INSENSITIVE_ORDER))
              .filter(
                  registrar ->
                      partialStringQuery.matches(registrar.getRegistrarName())
                          && ((cursorType != CursorType.REGISTRAR)
                              || (registrar.getRegistrarName().compareTo(cursorQueryString.get())
                                  > 0))
                          && shouldBeVisible(registrar))
              .limit(rdapResultSetMaxSize + 1)
              .collect(toImmutableList());
    }
    // Get the contact matches and return the results, fetching an additional contact to detect
    // truncation. Don't bother searching for contacts by name if the request would not be able to
    // see any names anyway. Also, if a registrar cursor is present, we have already moved past the
    // contacts, and don't need to fetch them this time. We can skip contacts if subtype is
    // REGISTRARS.
    RdapResultSet<ContactResource> resultSet;
    if (subtype == Subtype.REGISTRARS) {
      resultSet = RdapResultSet.create(ImmutableList.of());
    } else {
      if ((rdapAuthorization.role() == RdapAuthorization.Role.PUBLIC)
          || (cursorType == CursorType.REGISTRAR)) {
        resultSet = RdapResultSet.create(ImmutableList.of());
      } else {
        if (tm().isOfy()) {
          Query<ContactResource> query =
              queryItems(
                  ContactResource.class,
                  "searchName",
                  partialStringQuery,
                  cursorQueryString, // if we get here and there's a cursor, it must be a contact
                  DeletedItemHandling.EXCLUDE,
                  rdapResultSetMaxSize + 1);
          if (!rdapAuthorization.role().equals(Role.ADMINISTRATOR)) {
            query = query.filter("currentSponsorClientId in", rdapAuthorization.registrarIds());
          }
          resultSet = getMatchingResources(query, false, rdapResultSetMaxSize + 1);
        } else {
          resultSet =
              jpaTm()
                  .transact(
                      () -> {
                        CriteriaQueryBuilder<ContactResource> builder =
                            queryItemsSql(
                                ContactResource.class,
                                "searchName",
                                partialStringQuery,
                                cursorQueryString,
                                DeletedItemHandling.EXCLUDE);
                        if (!rdapAuthorization.role().equals(Role.ADMINISTRATOR)) {
                          builder =
                              builder.whereFieldIsIn(
                                  "currentSponsorClientId", rdapAuthorization.registrarIds());
                        }
                        return getMatchingResourcesSql(builder, false, rdapResultSetMaxSize + 1);
                      });
        }
      }
    }
    return makeSearchResults(resultSet, registrars, QueryType.FULL_NAME);
  }

  /**
   * Searches for entities by handle, returning a JSON array of entity info maps.
   *
   * <p>Searches for deleted entities are treated like wildcard searches.
   *
   * <p>We don't allow suffixes after a wildcard in entity searches. Suffixes are used in domain
   * searches to specify a TLD, and in nameserver searches to specify a locally managed domain name.
   * In both cases, the suffix can be turned into an additional query filter field. For contacts,
   * there is no equivalent string suffix that can be used as a query filter, so we disallow use.
   */
  private EntitySearchResponse searchByHandle(
      final RdapSearchPattern partialStringQuery,
      CursorType cursorType,
      Optional<String> cursorQueryString,
      Subtype subtype) {
    if (partialStringQuery.getSuffix() != null) {
      throw new UnprocessableEntityException("Suffixes not allowed in entity handle searches");
    }
    // Handle queries without a wildcard (and not including deleted) -- load by ID.
    if (!partialStringQuery.getHasWildcard() && !shouldIncludeDeleted()) {
      ImmutableList<ContactResource> contactResourceList;
      if (subtype == Subtype.REGISTRARS) {
        contactResourceList = ImmutableList.of();
      } else {
        Optional<ContactResource> contactResource =
            transactIfJpaTm(
                () ->
                    tm().loadByKeyIfPresent(
                            VKey.create(
                                ContactResource.class, partialStringQuery.getInitialString())));
        contactResourceList =
            (contactResource.isPresent() && shouldBeVisible(contactResource.get()))
                ? ImmutableList.of(contactResource.get())
                : ImmutableList.of();
      }
      ImmutableList<Registrar> registrarList;
      if (subtype == Subtype.CONTACTS) {
        registrarList = ImmutableList.of();
      } else {
        registrarList = getMatchingRegistrars(partialStringQuery.getInitialString());
      }
      return makeSearchResults(
          contactResourceList,
          IncompletenessWarningType.COMPLETE,
          contactResourceList.size(),
          registrarList,
          QueryType.HANDLE);
    // Handle queries with a wildcard (or including deleted), but no suffix. Because the handle
    // for registrars is the IANA identifier number, don't allow wildcard searches for registrars,
    // by simply not searching for registrars if a wildcard is present (unless the request is for
    // all registrars, in which case we know what to do). Fetch an extra contact to detect result
    // set truncation.
    } else {
      ImmutableList<Registrar> registrars;
      if ((subtype == Subtype.REGISTRARS)
          && partialStringQuery.getHasWildcard()
          && partialStringQuery.getInitialString().isEmpty()) {
        // Even though we are searching by IANA identifier, we should still sort by name, because
        // the IANA identifier can by missing, and sorting on that would screw up our cursors.
        registrars =
            Streams.stream(Registrar.loadAllCached())
                .sorted(
                    Comparator.comparing(
                        Registrar::getRegistrarName, String.CASE_INSENSITIVE_ORDER))
                .filter(
                    registrar ->
                        ((cursorType != CursorType.REGISTRAR)
                                || (registrar.getRegistrarName().compareTo(cursorQueryString.get())
                                    > 0))
                            && shouldBeVisible(registrar))
                .limit(rdapResultSetMaxSize + 1)
                .collect(toImmutableList());
      } else if ((subtype == Subtype.CONTACTS) || partialStringQuery.getHasWildcard()) {
        registrars = ImmutableList.of();
      } else {
        registrars = getMatchingRegistrars(partialStringQuery.getInitialString());
      }
      // Get the contact matches and return the results, fetching an additional contact to detect
      // truncation. If we are including deleted entries, we must fetch more entries, in case some
      // get excluded due to permissioning. Any cursor present must be a contact cursor, because we
      // would never return a registrar for this search.
      int querySizeLimit = getStandardQuerySizeLimit();
      RdapResultSet<ContactResource> contactResultSet;
      if (subtype == Subtype.REGISTRARS) {
        contactResultSet = RdapResultSet.create(ImmutableList.of());
      } else {
        if (tm().isOfy()) {
          contactResultSet =
              getMatchingResources(
                  queryItemsByKey(
                      ContactResource.class,
                      partialStringQuery,
                      cursorQueryString,
                      getDeletedItemHandling(),
                      querySizeLimit),
                  shouldIncludeDeleted(),
                  querySizeLimit);
        } else {
          contactResultSet =
              jpaTm()
                  .transact(
                      () ->
                          getMatchingResourcesSql(
                              queryItemsByKeySql(
                                  ContactResource.class,
                                  partialStringQuery,
                                  cursorQueryString,
                                  getDeletedItemHandling()),
                              shouldIncludeDeleted(),
                              querySizeLimit));
        }
      }
      return makeSearchResults(contactResultSet, registrars, QueryType.HANDLE);
    }
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

  /**
   * Builds a JSON array of entity info maps based on the specified contacts and registrars.
   *
   * <p>This is a convenience wrapper for the four-argument makeSearchResults; it unpacks the
   * properties of the {@link RdapResultSet} structure and passes them as separate arguments.
   */
  private EntitySearchResponse makeSearchResults(
      RdapResultSet<ContactResource> resultSet, List<Registrar> registrars, QueryType queryType) {
    return makeSearchResults(
        resultSet.resources(),
        resultSet.incompletenessWarningType(),
        resultSet.numResourcesRetrieved(),
        registrars,
        queryType);
  }

  /**
   * Builds a JSON array of entity info maps based on the specified contacts and registrars.
   *
   * <p>The number of contacts retrieved is recorded for use by the metrics.
   *
   * @param contacts the list of contacts which can be returned
   * @param incompletenessWarningType MIGHT_BE_INCOMPLETE if the list of contacts might be
   *     incomplete; this only matters if the total count of contacts and registrars combined is
   *     less than a full result set's worth
   * @param numContactsRetrieved the number of contacts retrieved in the process of generating the
   *     results
   * @param registrars the list of registrars which can be returned
   * @param queryType whether the query was by full name or by handle
   * @return an {@link RdapSearchResults} object
   */
  private EntitySearchResponse makeSearchResults(
      List<ContactResource> contacts,
      IncompletenessWarningType incompletenessWarningType,
      int numContactsRetrieved,
      List<Registrar> registrars,
      QueryType queryType) {

    metricInformationBuilder.setNumContactsRetrieved(numContactsRetrieved);

    // Determine what output data type to use, depending on whether more than one entity will be
    // returned.
    OutputDataType outputDataType =
        (contacts.size() + registrars.size() > 1) ? OutputDataType.SUMMARY : OutputDataType.FULL;

    // There can be more results than our max size, partially because we have two pools to draw from
    // (contacts and registrars), and partially because we try to fetch one more than the max size,
    // so we can tell whether to display the truncation notification.
    //
    // Each time we add a contact or registrar to the output data set, remember what the appropriate
    // cursor would be if it were the last item returned. When we stop adding items, the last cursor
    // value we remembered will be the right one to pass back.
    EntitySearchResponse.Builder builder =
        EntitySearchResponse.builder()
            .setIncompletenessWarningType(incompletenessWarningType);
    Optional<String> newCursor = Optional.empty();
    for (ContactResource contact : Iterables.limit(contacts, rdapResultSetMaxSize)) {
      // As per Andy Newton on the regext mailing list, contacts by themselves have no role, since
      // they are global, and might have different roles for different domains.
      builder
          .entitySearchResultsBuilder()
          .add(
              rdapJsonFormatter.createRdapContactEntity(
                  contact, ImmutableSet.of(), outputDataType));
      newCursor =
          Optional.of(
              CONTACT_CURSOR_PREFIX
                  + ((queryType == QueryType.FULL_NAME)
                      ? contact.getSearchName()
                      : contact.getRepoId()));
    }
    if (rdapResultSetMaxSize > contacts.size()) {
      for (Registrar registrar :
          Iterables.limit(registrars, rdapResultSetMaxSize - contacts.size())) {
        builder
            .entitySearchResultsBuilder()
            .add(rdapJsonFormatter.createRdapRegistrarEntity(registrar, outputDataType));
        newCursor = Optional.of(REGISTRAR_CURSOR_PREFIX + registrar.getRegistrarName());
      }
    }
    if (rdapResultSetMaxSize < contacts.size() + registrars.size()) {
      builder.setNextPageUri(createNavigationUri(newCursor.get()));
      builder.setIncompletenessWarningType(IncompletenessWarningType.TRUNCATED);
      return builder.build();
    }
    return builder.build();
  }
}
