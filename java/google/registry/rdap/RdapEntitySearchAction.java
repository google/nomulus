// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Booleans;
import com.googlecode.objectify.Key;
import google.registry.config.ConfigModule.Config;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.RdapJsonFormatter.BoilerplateType;
import google.registry.request.Action;
import google.registry.request.HttpException;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.HttpException.UnprocessableEntityException;
import google.registry.request.Parameter;
import google.registry.util.Clock;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * RDAP (new WHOIS) action for entity (contact and registrar) search requests.
 *
 * <p>All commands and responses conform to the RDAP spec as defined in RFCs 7480 through 7485.
 *
 * @see <a href="http://tools.ietf.org/html/rfc7482">
 *        RFC 7482: Registration Data Access Protocol (RDAP) Query Format</a>
 * @see <a href="http://tools.ietf.org/html/rfc7483">
 *        RFC 7483: JSON Responses for the Registration Data Access Protocol (RDAP)</a>
 */
@Action(path = RdapEntitySearchAction.PATH, method = {GET, HEAD}, isPrefix = true)
public class RdapEntitySearchAction extends RdapActionBase {

  public static final String PATH = "/rdap/entities";

  @Inject Clock clock;
  @Inject @Parameter("fn") Optional<String> fnParam;
  @Inject @Parameter("handle") Optional<String> handleParam;
  @Inject @Config("rdapResultSetMaxSize") int rdapResultSetMaxSize;
  @Inject RdapEntitySearchAction() {}

  @Override
  public String getHumanReadableObjectTypeName() {
    return "entity search";
  }

  @Override
  public String getActionPath() {
    return PATH;
  }

  /** Parses the parameters and calls the appropriate search function. */
  @Override
  public ImmutableMap<String, Object> getJsonObjectForResource(
      String pathSearchString, boolean isHeadRequest, String linkBase) throws HttpException {
    DateTime now = clock.nowUtc();
    // RDAP syntax example: /rdap/entities?fn=Bobby%20Joe*.
    // The pathSearchString is not used by search commands.
    if (pathSearchString.length() > 0) {
      throw new BadRequestException("Unexpected path");
    }
    if (Booleans.countTrue(fnParam.isPresent(), handleParam.isPresent()) != 1) {
      throw new BadRequestException("You must specify either fn=XXXX or handle=YYYY");
    }
    ImmutableList<ImmutableMap<String, Object>> results;
    if (fnParam.isPresent()) {
      // syntax: /rdap/entities?fn=Bobby%20Joe*
      // The name is the contact name or registrar name (not registrar contact name).
      results = searchByName(RdapSearchPattern.create(fnParam.get(), false), now);
    } else {
      // syntax: /rdap/entities?handle=12345-*
      // The handle is either the contact roid or the registrar clientId.
      results = searchByHandle(RdapSearchPattern.create(handleParam.get(), false), now);
    }
    if (results.isEmpty()) {
      throw new NotFoundException("No entities found");
    }
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
    builder.put("entitySearchResults", results);
    RdapJsonFormatter.addTopLevelEntries(builder, BoilerplateType.ENTITY, null, rdapLinkBase);
    return builder.build();
  }

  /**
   * Searches for entities by name, returning a JSON array of entity info maps.
   *
   * <p>As per Gustavo Lozano of ICANN, registrar name search should be by registrar name only, not
   * by registrar contact name:
   *
   * <p>The search is by registrar name only. The profile is supporting the functionality defined
   * in the Base Registry Agreement (see 1.6 of Section 4 of the Base Registry Agreement,
   * https://newgtlds.icann.org/sites/default/files/agreements/
   * agreement-approved-09jan14-en.htm).
   *
   * <p>According to RFC 7482 section 6.1, punycode is only used for domain name labels, so we can
   * assume that entity names are regular unicode.
   */
  private ImmutableList<ImmutableMap<String, Object>>
      searchByName(final RdapSearchPattern partialStringQuery, DateTime now) throws HttpException {
    // Handle queries without a wildcard -- load by name, which may not be unique.
    if (!partialStringQuery.getHasWildcard()) {
      Registrar registrar = Registrar.loadByName(partialStringQuery.getInitialString());
      return makeSearchResults(
          ofy().load()
              .type(ContactResource.class)
              .filter("searchName", partialStringQuery.getInitialString())
              .filter("deletionTime", END_OF_TIME)
              .limit(rdapResultSetMaxSize),
          (registrar == null)
              ? ImmutableList.<Registrar>of() : ImmutableList.of(registrar),
          now);
    // Handle queries with a wildcard, but no suffix. For contact resources, the deletion time will
    // always be END_OF_TIME for non-deleted records; unlike domain resources, we don't need to
    // worry about deletion times in the future. That allows us to use an equality query for the
    // deletion time.
    } else if (partialStringQuery.getSuffix() == null) {
      return makeSearchResults(
          ofy().load()
              .type(ContactResource.class)
              .filter("searchName >=", partialStringQuery.getInitialString())
              .filter("searchName <", partialStringQuery.getNextInitialString())
              .filter("deletionTime", END_OF_TIME)
              .limit(rdapResultSetMaxSize),
          Registrar.loadByNameRange(
              partialStringQuery.getInitialString(),
              partialStringQuery.getNextInitialString(),
              rdapResultSetMaxSize),
          now);
    // Don't allow suffixes in entity name search queries.
    } else {
      throw new UnprocessableEntityException("Suffixes not allowed in entity name searches");
    }
  }

  /** Searches for entities by handle, returning a JSON array of entity info maps. */
  private ImmutableList<ImmutableMap<String, Object>> searchByHandle(
      final RdapSearchPattern partialStringQuery, DateTime now) throws HttpException {
    // Handle queries without a wildcard -- load by ID.
    if (!partialStringQuery.getHasWildcard()) {
      ContactResource contactResource = ofy().load()
          .type(ContactResource.class)
          .id(partialStringQuery.getInitialString())
          .now();
      ImmutableList<Registrar> registrars = getMatchingRegistrars(partialStringQuery);
      return makeSearchResults(
          ((contactResource == null) || !contactResource.getDeletionTime().isEqual(END_OF_TIME))
              ? ImmutableList.<ContactResource>of() : ImmutableList.of(contactResource),
          registrars,
          now);
    // Handle queries with a wildcard, but no suffix. For contact resources, the deletion time will
    // always be END_OF_TIME for non-deleted records; unlike domain resources, we don't need to
    // worry about deletion times in the future. That allows us to use an equality query for the
    // deletion time. Because the handle for registrars is the IANA identifier number, don't allow
    // wildcard searches for registrars, by simply not searching for registrars if a wildcard is
    // present.
    } else if (partialStringQuery.getSuffix() == null) {
      return makeSearchResults(
          ofy().load()
              .type(ContactResource.class)
              .filterKey(
                  ">=", Key.create(ContactResource.class, partialStringQuery.getInitialString()))
              .filterKey(
                  "<", Key.create(ContactResource.class, partialStringQuery.getNextInitialString()))
              .filter("deletionTime", END_OF_TIME)
              .limit(rdapResultSetMaxSize),
          ImmutableList.<Registrar>of(),
          now);
    // Don't allow suffixes in entity handle search queries.
    } else {
      throw new UnprocessableEntityException("Suffixes not allowed in entity handle searches");
    }
  }

  /** Looks up registrars by handle (i.e. IANA identifier). */
  private ImmutableList<Registrar>
      getMatchingRegistrars(final RdapSearchPattern partialStringQuery) {
    Long ianaIdentifier;
    try {
      ianaIdentifier = Long.parseLong(partialStringQuery.getInitialString());
    } catch (NumberFormatException e) {
      return ImmutableList.of();
    }
    return ImmutableList.copyOf(Registrar.loadByIanaIdentifierRange(
        ianaIdentifier, ianaIdentifier + 1, rdapResultSetMaxSize));
  }

  /** Builds a JSON array of entity info maps based on the specified contacts and registrars. */
  private ImmutableList<ImmutableMap<String, Object>> makeSearchResults(
      Iterable<ContactResource> contactResources, Iterable<Registrar> registrars, DateTime now)
      throws HttpException {
    ImmutableList.Builder<ImmutableMap<String, Object>> builder = new ImmutableList.Builder<>();
    for (ContactResource contact : contactResources) {
      // As per Andy Newton on the regext mailing list, contacts by themselves have no role, since
      // they are global, and might have different roles for different domains.
      builder.add(RdapJsonFormatter.makeRdapJsonForContact(
          contact,
          false,
          Optional.<DesignatedContact.Type>absent(),
          rdapLinkBase,
          rdapWhoisServer, now));
    }
    for (Registrar registrar : registrars) {
      if (registrar.isActiveAndPubliclyVisible()) {
        builder.add(RdapJsonFormatter.makeRdapJsonForRegistrar(
            registrar, false, rdapLinkBase, rdapWhoisServer, now));
      }
    }
    // In theory, there could be more results than our max size, so limit the size.
    ImmutableList<ImmutableMap<String, Object>> resultSet = builder.build();
    return (resultSet.size() <= rdapResultSetMaxSize)
        ? resultSet
        : resultSet.subList(0, rdapResultSetMaxSize);
  }
}
