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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.re2j.Pattern;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.HttpException;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.util.Clock;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * RDAP (new WHOIS) action for entity (contact and registrar) requests. the ICANN operational
 * profile dictates that the "handle" for registrars is to be the IANA registrar ID:
 *
 * <p>2.8.3. Registries MUST support lookup for entities with the registrar role within other
 * objects using the handle (as described in 3.1.5 of RFC7482). The handle of the entity with the
 * registrar role MUST be equal to IANA Registrar ID. The entity with the registrar role in the RDAP
 * response MUST contain a publicIDs member to identify the IANA Registrar ID from the IANA’s
 * Registrar ID registry. The type value of the publicID object MUST be equal to IANA Registrar ID.
 */
@Action(path = RdapEntityAction.PATH, method = {GET, HEAD}, isPrefix = true)
public class RdapEntityAction extends RdapActionBase {

  public static final String PATH = "/rdap/entity/";

  private static final Pattern ROID_PATTERN = Pattern.compile("[-.a-zA-Z0-9]+");

  @Inject Clock clock;
  @Inject RdapEntityAction() {}

  @Override
  public String getHumanReadableObjectTypeName() {
    return "entity";
  }

  @Override
  public String getActionPath() {
    return PATH;
  }

  @Override
  public ImmutableMap<String, Object> getJsonObjectForResource(
      String pathSearchString, boolean isHeadRequest, String linkBase) throws HttpException {
    DateTime now = clock.nowUtc();
    // The query string is not used; the RDAP syntax is /rdap/entity/handle (the handle is the roid
    // for contacts and the client identifier for registrars). Since RDAP's concept of an entity
    // includes both contacts and registrars, search for one first, then the other.
    boolean wasValidKey = false;
    if (ROID_PATTERN.matcher(pathSearchString).matches()) {
      wasValidKey = true;
      Key<ContactResource> contactKey = Key.create(ContactResource.class, pathSearchString);
      ContactResource contactResource = ofy().load().key(contactKey).now();
      // As per Andy Newton on the regext mailing list, contacts by themselves have no role, since
      // they are global, and might have different roles for different domains.
      if ((contactResource != null) && now.isBefore(contactResource.getDeletionTime())) {
        return RdapJsonFormatter.makeRdapJsonForContact(
            contactResource,
            true,
            Optional.<DesignatedContact.Type>absent(),
            rdapLinkBase,
            rdapWhoisServer,
            now);
      }
    }
    try {
      Long ianaIdentifier = Long.parseLong(pathSearchString);
      wasValidKey = true;
      Registrar registrar = Iterables.getOnlyElement(
          Registrar.loadByIanaIdentifierRange(ianaIdentifier, ianaIdentifier + 1, 1), null);
      if ((registrar != null) && registrar.isActiveAndPubliclyVisible()) {
        return RdapJsonFormatter.makeRdapJsonForRegistrar(
            registrar, true, rdapLinkBase, rdapWhoisServer, now);
      }
    } catch (NumberFormatException e) {
      // Although the search string was not a valid IANA identifier, it might still have been a
      // valid ROID.
    }
    // At this point, we have failed to find either a contact or a registrar.
    throw wasValidKey
        ? new NotFoundException(pathSearchString + " not found")
        : new BadRequestException(pathSearchString + " is not a valid entity handle");
  }
}

