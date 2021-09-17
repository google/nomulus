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

import static com.google.common.base.Charsets.UTF_8;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.EppResource;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.BaseSearchResponse;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.UnprocessableEntityException;
import google.registry.request.Parameter;
import google.registry.request.ParameterMap;
import google.registry.request.RequestUrl;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import javax.persistence.criteria.CriteriaBuilder;

/**
 * Base RDAP (new WHOIS) action for domain, nameserver and entity search requests.
 *
 * @see <a href="https://tools.ietf.org/html/rfc9082">RFC 9082: Registration Data Access Protocol
 *     (RDAP) Query Format</a>
 */
public abstract class RdapSearchActionBase extends RdapActionBase {

  private static final int RESULT_SET_SIZE_SCALING_FACTOR = 30;

  @Inject @RequestUrl String requestUrl;
  @Inject @ParameterMap ImmutableListMultimap<String, String> parameterMap;
  @Inject @Parameter("cursor") Optional<String> cursorTokenParam;
  @Inject @Parameter("registrar") Optional<String> registrarParam;

  protected Optional<String> cursorString;

  RdapSearchActionBase(String humanReadableObjectTypeName, EndpointType endpointType) {
    super(humanReadableObjectTypeName, endpointType);
  }

  @Override
  public final BaseSearchResponse getJsonObjectForResource(
      String pathSearchString, boolean isHeadRequest) {
    // The pathSearchString is not used by search commands.
    if (pathSearchString.length() > 0) {
      throw new BadRequestException("Unexpected path");
    }
    decodeCursorToken();
    metricInformationBuilder.setRegistrarSpecified(registrarParam.isPresent());
    return getSearchResponse(isHeadRequest);
  }

  public abstract BaseSearchResponse getSearchResponse(boolean isHeadRequest);

  /**
   * Decodes the cursor token passed in the HTTP request.
   *
   * <p>The cursor token is just the Base 64 encoded value of the last data item returned. To fetch
   * the next page, the code can just decode the cursor, and return only data whose value is greater
   * than the cursor value.
   */
  protected void decodeCursorToken() {
    cursorString =
        cursorTokenParam.map(
            cursor -> new String(Base64.getDecoder().decode(cursor.getBytes(UTF_8)), UTF_8));
  }

  /** Returns an encoded cursor token to pass back in the RDAP JSON link strings. */
  protected String encodeCursorToken(String nextCursorString) {
    return new String(Base64.getEncoder().encode(nextCursorString.getBytes(UTF_8)), UTF_8);
  }

  /** Returns the original request URL, but with the specified parameter added or overridden. */
  protected String getRequestUrlWithExtraParameter(String parameterName, String parameterValue) {
    return getRequestUrlWithExtraParameter(parameterName, ImmutableList.of(parameterValue));
  }

  /** Returns the registrar on which results should be filtered, or absent(). */
  protected Optional<String> getDesiredRegistrar() {
    return registrarParam;
  }

  protected boolean shouldBeVisible(Optional<? extends EppResource> eppResource) {
    return eppResource.isPresent() && shouldBeVisible(eppResource.get());
  }

  /**
   * Returns true if the EPP resource should be visible.
   *
   * <p>This is true iff:
   * 1. The resource is not deleted, or the request wants to see deleted items, and is authorized to
   *    do so, and:
   * 2. The request did not specify a registrar to filter on, or the registrar matches.
   */
  protected boolean shouldBeVisible(EppResource eppResource) {
    return isAuthorized(eppResource)
        && (!registrarParam.isPresent()
            || registrarParam.get().equals(eppResource.getPersistedCurrentSponsorRegistrarId()));
  }

  /**
   * Returns true if the EPP resource should be visible.
   *
   * <p>This is true iff:
   * 1. The resource is not deleted, or the request wants to see deleted items, and is authorized to
   *    do so, and:
   * 2. The request did not specify a registrar to filter on, or the registrar matches.
   */
  protected boolean shouldBeVisible(Registrar registrar) {
    return isAuthorized(registrar)
        && (!registrarParam.isPresent() || registrarParam.get().equals(registrar.getRegistrarId()));
  }

  /**
   * Runs the given query, and checks for permissioning if necessary.
   *
   * @param query an already-defined query to be run; a filter on currentSponsorClientId will be
   *     added if appropriate
   * @param checkForVisibility true if the results should be checked to make sure they are visible;
   *     normally this should be equal to the shouldIncludeDeleted setting, but in cases where the
   *     query could not check deletion status (due to Datastore limitations such as the limit of
   *     one field queried for inequality, for instance), it may need to be set to true even when
   *     not including deleted records
   * @param querySizeLimit the maximum number of items the query is expected to return, usually
   *     because the limit has been set
   * @return an {@link RdapResultSet} object containing the list of resources and an incompleteness
   *     warning flag, which is set to MIGHT_BE_INCOMPLETE iff any resources were excluded due to
   *     lack of visibility, and the resulting list of resources is less than the maximum allowable,
   *     and the number of items returned by the query is greater than or equal to the maximum
   *     number we might have expected
   */
  <T extends EppResource> RdapResultSet<T> getMatchingResources(
      Query<T> query, boolean checkForVisibility, int querySizeLimit) {
    Optional<String> desiredRegistrar = getDesiredRegistrar();
    if (desiredRegistrar.isPresent()) {
      query = query.filter("currentSponsorClientId", desiredRegistrar.get());
    }
    List<T> queryResult = query.list();
    if (checkForVisibility) {
      return filterResourcesByVisibility(queryResult, querySizeLimit);
    } else {
      return RdapResultSet.create(queryResult);
    }
  }

  /**
   * In Cloud SQL, builds and runs the given query, and checks for permissioning if necessary.
   *
   * @param builder a query builder that represents the various SELECT FROM, WHERE, ORDER BY and
   *     (etc) clauses that make up this SQL query
   * @param checkForVisibility true if the results should be checked to make sure they are visible;
   *     normally this should be equal to the shouldIncludeDeleted setting, but in cases where the
   *     query could not check deletion status (due to Datastore limitations such as the limit of
   *     one field queried for inequality, for instance), it may need to be set to true even when
   *     not including deleted records
   * @param querySizeLimit the maximum number of items the query is expected to return, usually
   *     because the limit has been set
   * @return an {@link RdapResultSet} object containing the list of resources and an incompleteness
   *     warning flag, which is set to MIGHT_BE_INCOMPLETE iff any resources were excluded due to
   *     lack of visibility, and the resulting list of resources is less than the maximum allowable,
   *     and the number of items returned by the query is greater than or equal to the maximum
   *     number we might have expected
   */
  <T extends EppResource> RdapResultSet<T> getMatchingResourcesSql(
      CriteriaQueryBuilder<T> builder, boolean checkForVisibility, int querySizeLimit) {
    jpaTm().assertInTransaction();
    Optional<String> desiredRegistrar = getDesiredRegistrar();
    if (desiredRegistrar.isPresent()) {
      builder =
          builder.where(
              "currentSponsorClientId", jpaTm().getEntityManager().getCriteriaBuilder()::equal,
              desiredRegistrar.get());
    }
    List<T> queryResult =
        jpaTm().criteriaQuery(builder.build()).setMaxResults(querySizeLimit).getResultList();
    if (checkForVisibility) {
      return filterResourcesByVisibility(queryResult, querySizeLimit);
    } else {
      return RdapResultSet.create(queryResult);
    }
  }

  protected <T extends EppResource> RdapResultSet<T> filterResourcesByVisibility(
      List<T> queryResult, int querySizeLimit) {
    // If we are including deleted resources, we need to check that we're authorized for each one.
    List<T> resources = new ArrayList<>();
    int numResourcesQueried = 0;
    boolean someExcluded = false;
    for (T resource : queryResult) {
      if (shouldBeVisible(resource)) {
        resources.add(resource);
      } else {
        someExcluded = true;
      }
      numResourcesQueried++;
      if (resources.size() > rdapResultSetMaxSize) {
        break;
      }
    }
    // The incompleteness problem comes about because we don't know how many items to fetch. We want
    // to return rdapResultSetMaxSize worth of items, but some might be excluded, so we fetch more
    // just in case. But how many more? That's the potential problem, addressed with the three way
    // AND statement:
    // 1. If we didn't exclude any items, then we can't have the incompleteness problem.
    // 2. If have a full result set batch (rdapResultSetMaxSize items), we must by definition be
    //    giving the user a complete result set.
    // 3. If we started with fewer than querySizeLimit items, then there weren't any more items that
    //    we missed. Even if we return fewer than rdapResultSetMaxSize items, it isn't because we
    //    didn't fetch enough to start.
    // Only if all three conditions are true might things be incomplete. In other words, we fetched
    // as many as our limit allowed, but then excluded so many that we wound up with less than a
    // full result set's worth of results.
    return RdapResultSet.create(
        resources,
        (someExcluded
                && (resources.size() < rdapResultSetMaxSize)
                && (numResourcesQueried >= querySizeLimit))
            ? IncompletenessWarningType.MIGHT_BE_INCOMPLETE
            : IncompletenessWarningType.COMPLETE,
        numResourcesQueried);
  }

  RdapSearchPattern recordWildcardType(RdapSearchPattern partialStringQuery) {
    if (!partialStringQuery.getHasWildcard()) {
      metricInformationBuilder.setWildcardType(WildcardType.NO_WILDCARD);
    } else if (partialStringQuery.getSuffix() == null) {
      metricInformationBuilder.setWildcardType(WildcardType.PREFIX);
    } else if (partialStringQuery.getInitialString().isEmpty()) {
      metricInformationBuilder.setWildcardType(WildcardType.SUFFIX);
    } else {
      metricInformationBuilder.setWildcardType(WildcardType.PREFIX_AND_SUFFIX);
    }
    metricInformationBuilder.setPrefixLength(partialStringQuery.getInitialString().length());
    return partialStringQuery;
  }

  /**
   * Returns the original request URL, but with the specified parameter added or overridden.
   *
   * <p>This version handles a list of parameter values, all associated with the same name.
   *
   * <p>Example: If the original parameters were "a=w&amp;a=x&amp;b=y&amp;c=z", and this method is
   * called with parameterName = "b" and parameterValues of "p" and "q", the result will be
   * "a=w&amp;a=x&amp;c=z&amp;b=p&amp;b=q". The new values of parameter "b" replace the old ones.
   */
  protected String getRequestUrlWithExtraParameter(
      String parameterName, List<String> parameterValues) {
    StringBuilder stringBuilder = new StringBuilder(requestUrl);
    boolean first = true;
    // Step one: loop through the existing parameters, copying all of them except for the parameter
    // we want to explicitly set.
    for (Map.Entry<String, String> entry : parameterMap.entries()) {
      if (!entry.getKey().equals(parameterName)) {
        appendParameter(stringBuilder, entry.getKey(), entry.getValue(), first);
        first = false;
      }
    }
    // Step two: tack on all values of the explicit parameter.
    for (String parameterValue : parameterValues) {
      appendParameter(stringBuilder, parameterName, parameterValue, first);
      first = false;
    }
    return stringBuilder.toString();
  }

  private void appendParameter(
      StringBuilder stringBuilder, String name, String value, boolean first) {
    try {
      stringBuilder.append(first ? '?' : '&');
      stringBuilder.append(URLEncoder.encode(name, "UTF-8"));
      stringBuilder.append('=');
      stringBuilder.append(URLEncoder.encode(value, "UTF-8"));
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  /** Creates the URL for this same search with a different starting point cursor. */
  URI createNavigationUri(String cursor) {
    return URI.create(getRequestUrlWithExtraParameter("cursor", encodeCursorToken(cursor)));
  }

  // We want to return rdapResultSetMaxSize + 1 results, so that we know if there are "extra"
  // results (in which case we'll have a "next" link in the RDAP response).
  // In case that we want to return deleted results as well, we have to scale the number of results
  // to be (more) sure we got everything.
  int getStandardQuerySizeLimit() {
    return shouldIncludeDeleted()
        ? (RESULT_SET_SIZE_SCALING_FACTOR * (rdapResultSetMaxSize + 1))
        : (rdapResultSetMaxSize + 1);
  }

  /**
   * Handles prefix searches in cases where, if we need to filter out deleted items, there are no
   * pending deletes.
   *
   * <p>In such cases, it is sufficient to check whether {@code deletionTime} is equal to
   * {@code END_OF_TIME}, because any other value means it has already been deleted. This allows us
   * to use an equality query for the deletion time.
   *
   * @param clazz the type of resource to be queried
   * @param filterField the database field of interest
   * @param partialStringQuery the details of the search string; if there is no wildcard, an
   *        equality query is used; if there is a wildcard, a range query is used instead; the
   *        initial string should not be empty, and any search suffix will be ignored, so the caller
   *        must filter the results if a suffix is specified
   * @param cursorString if a cursor is present, this parameter should specify the cursor string, to
   *        skip any results up to and including the string; empty() if there is no cursor
   * @param deletedItemHandling whether to include or exclude deleted items
   * @param resultSetMaxSize the maximum number of results to return
   * @return the query object
   */
  static <T extends EppResource> Query<T> queryItems(
      Class<T> clazz,
      String filterField,
      RdapSearchPattern partialStringQuery,
      Optional<String> cursorString,
      DeletedItemHandling deletedItemHandling,
      int resultSetMaxSize) {
    if (partialStringQuery.getInitialString().length()
        < RdapSearchPattern.MIN_INITIAL_STRING_LENGTH) {
      throw new UnprocessableEntityException(
          String.format(
              "Initial search string must be at least %d characters",
              RdapSearchPattern.MIN_INITIAL_STRING_LENGTH));
    }
    Query<T> query = auditedOfy().load().type(clazz);
    if (!partialStringQuery.getHasWildcard()) {
      query = query.filter(filterField, partialStringQuery.getInitialString());
    } else {
      // Ignore the suffix; the caller will need to filter on the suffix, if any.
      query =
          query
              .filter(filterField + " >=", partialStringQuery.getInitialString())
              .filter(filterField + " <", partialStringQuery.getNextInitialString());
    }
    if (cursorString.isPresent()) {
      query = query.filter(filterField + " >", cursorString.get());
    }
    return setOtherQueryAttributes(query, deletedItemHandling, resultSetMaxSize);
  }

  /**
   * In Cloud SQL, handles prefix searches in cases where, if we need to filter out deleted items,
   * there are no pending deletes.
   *
   * <p>In such cases, it is sufficient to check whether {@code deletionTime} is equal to {@code
   * END_OF_TIME}, because any other value means it has already been deleted. This allows us to use
   * an equality query for the deletion time.
   *
   * @param clazz the type of resource to be queried
   * @param filterField the database field of interest
   * @param partialStringQuery the details of the search string; if there is no wildcard, an
   *     equality query is used; if there is a wildcard, a range query is used instead; the initial
   *     string should not be empty, and any search suffix will be ignored, so the caller must
   *     filter the results if a suffix is specified
   * @param cursorString if a cursor is present, this parameter should specify the cursor string, to
   *     skip any results up to and including the string; empty() if there is no cursor
   * @param deletedItemHandling whether to include or exclude deleted items
   * @return a {@link CriteriaQueryBuilder} object representing the query so far
   */
  static <T extends EppResource> CriteriaQueryBuilder<T> queryItemsSql(
      Class<T> clazz,
      String filterField,
      RdapSearchPattern partialStringQuery,
      Optional<String> cursorString,
      DeletedItemHandling deletedItemHandling) {
    jpaTm().assertInTransaction();
    if (partialStringQuery.getInitialString().length()
        < RdapSearchPattern.MIN_INITIAL_STRING_LENGTH) {
      throw new UnprocessableEntityException(
          String.format(
              "Initial search string must be at least %d characters",
              RdapSearchPattern.MIN_INITIAL_STRING_LENGTH));
    }
    CriteriaBuilder criteriaBuilder = jpaTm().getEntityManager().getCriteriaBuilder();
    CriteriaQueryBuilder<T> builder = CriteriaQueryBuilder.create(clazz);
    if (partialStringQuery.getHasWildcard()) {
      builder =
          builder.where(
              filterField, criteriaBuilder::like,
              String.format("%s%%", partialStringQuery.getInitialString()));
    } else {
      // no wildcard means we use a standard equals query
      builder =
          builder.where(filterField, criteriaBuilder::equal, partialStringQuery.getInitialString());
    }
    if (cursorString.isPresent()) {
      builder = builder.where(filterField, criteriaBuilder::greaterThan, cursorString.get());
    }
    builder = builder.orderByAsc(filterField);
    return setDeletedItemHandlingSql(builder, deletedItemHandling);
  }

  /**
   * Handles searches using a simple string rather than an {@link RdapSearchPattern}.
   *
   * <p>Since the filter is not an inequality, we can support also checking a cursor string against
   * a different field (which involves an inequality on that field).
   *
   * @param clazz the type of resource to be queried
   * @param filterField the database field of interest
   * @param queryString the search string
   * @param cursorField the field which should be compared to the cursor string, or empty() if the
   *     key should be compared to a key created from the cursor string
   * @param cursorString if a cursor is present, this parameter should specify the cursor string, to
   *     skip any results up to and including the string; empty() if there is no cursor
   * @param deletedItemHandling whether to include or exclude deleted items
   * @param resultSetMaxSize the maximum number of results to return
   * @return the query object
   */
  static <T extends EppResource> Query<T> queryItems(
      Class<T> clazz,
      String filterField,
      String queryString,
      Optional<String> cursorField,
      Optional<String> cursorString,
      DeletedItemHandling deletedItemHandling,
      int resultSetMaxSize) {
    if (queryString.length() < RdapSearchPattern.MIN_INITIAL_STRING_LENGTH) {
      throw new UnprocessableEntityException(
          String.format(
              "Initial search string must be at least %d characters",
              RdapSearchPattern.MIN_INITIAL_STRING_LENGTH));
    }
    Query<T> query = auditedOfy().load().type(clazz).filter(filterField, queryString);
    if (cursorString.isPresent()) {
      if (cursorField.isPresent()) {
        query = query.filter(cursorField.get() + " >", cursorString.get());
      } else {
        query = query.filterKey(">", Key.create(clazz, cursorString.get()));
      }
    }
    return setOtherQueryAttributes(query, deletedItemHandling, resultSetMaxSize);
  }

  /**
   * In Cloud SQL, handles searches using a simple string rather than an {@link RdapSearchPattern}.
   *
   * <p>Since the filter is not an inequality, we can support also checking a cursor string against
   * a different field (which involves an inequality on that field).
   *
   * @param clazz the type of resource to be queried
   * @param filterField the database field of interest
   * @param queryString the search string
   * @param cursorField the field which should be compared to the cursor string, or empty() if the
   *     key should be compared to a key created from the cursor string
   * @param cursorString if a cursor is present, this parameter should specify the cursor string, to
   *     skip any results up to and including the string; empty() if there is no cursor
   * @param deletedItemHandling whether to include or exclude deleted items
   * @return a {@link CriteriaQueryBuilder} object representing the query so far
   */
  static <T extends EppResource> CriteriaQueryBuilder<T> queryItemsSql(
      Class<T> clazz,
      String filterField,
      String queryString,
      Optional<String> cursorField,
      Optional<String> cursorString,
      DeletedItemHandling deletedItemHandling) {
    if (queryString.length() < RdapSearchPattern.MIN_INITIAL_STRING_LENGTH) {
      throw new UnprocessableEntityException(
          String.format(
              "Initial search string must be at least %d characters",
              RdapSearchPattern.MIN_INITIAL_STRING_LENGTH));
    }
    jpaTm().assertInTransaction();
    CriteriaQueryBuilder<T> builder = CriteriaQueryBuilder.create(clazz);
    CriteriaBuilder criteriaBuilder = jpaTm().getEntityManager().getCriteriaBuilder();
    builder = builder.where(filterField, criteriaBuilder::equal, queryString);
    if (cursorString.isPresent()) {
      if (cursorField.isPresent()) {
        builder =
            builder.where(cursorField.get(), criteriaBuilder::greaterThan, cursorString.get());
      } else {
        builder = builder.where("repoId", criteriaBuilder::greaterThan, cursorString.get());
      }
    }
    return setDeletedItemHandlingSql(builder, deletedItemHandling);
  }

  /** Handles searches where the field to be searched is the key. */
  static <T extends EppResource> Query<T> queryItemsByKey(
      Class<T> clazz,
      RdapSearchPattern partialStringQuery,
      Optional<String> cursorString,
      DeletedItemHandling deletedItemHandling,
      int resultSetMaxSize) {
    if (partialStringQuery.getInitialString().length()
        < RdapSearchPattern.MIN_INITIAL_STRING_LENGTH) {
      throw new UnprocessableEntityException(
          String.format(
              "Initial search string must be at least %d characters",
              RdapSearchPattern.MIN_INITIAL_STRING_LENGTH));
    }
    Query<T> query = auditedOfy().load().type(clazz);
    if (!partialStringQuery.getHasWildcard()) {
      query = query.filterKey("=", Key.create(clazz, partialStringQuery.getInitialString()));
    } else {
      // Ignore the suffix; the caller will need to filter on the suffix, if any.
      query =
          query
              .filterKey(">=", Key.create(clazz, partialStringQuery.getInitialString()))
              .filterKey("<", Key.create(clazz, partialStringQuery.getNextInitialString()));
    }
    if (cursorString.isPresent()) {
      query = query.filterKey(">", Key.create(clazz, cursorString.get()));
    }
    return setOtherQueryAttributes(query, deletedItemHandling, resultSetMaxSize);
  }

  /** In Cloud SQL, handles searches where the field to be searched is the key. */
  static <T extends EppResource> CriteriaQueryBuilder<T> queryItemsByKeySql(
      Class<T> clazz,
      RdapSearchPattern partialStringQuery,
      Optional<String> cursorString,
      DeletedItemHandling deletedItemHandling) {
    jpaTm().assertInTransaction();
    return queryItemsSql(clazz, "repoId", partialStringQuery, cursorString, deletedItemHandling);
  }

  static <T extends EppResource> CriteriaQueryBuilder<T> setDeletedItemHandlingSql(
      CriteriaQueryBuilder<T> builder, DeletedItemHandling deletedItemHandling) {
    if (!Objects.equals(deletedItemHandling, DeletedItemHandling.INCLUDE)) {
      builder =
          builder.where(
              "deletionTime", jpaTm().getEntityManager().getCriteriaBuilder()::equal, END_OF_TIME);
    }
    return builder;
  }

  static <T extends EppResource> Query<T> setOtherQueryAttributes(
      Query<T> query, DeletedItemHandling deletedItemHandling, int resultSetMaxSize) {
    if (deletedItemHandling != DeletedItemHandling.INCLUDE) {
      query = query.filter("deletionTime", END_OF_TIME);
    }
    return query.limit(resultSetMaxSize);
  }
}
