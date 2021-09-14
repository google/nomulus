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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.rdap.RdapUtils.getRegistrarByIanaIdentifier;
import static google.registry.rdap.RdapUtils.getRegistrarByName;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Longs;
import com.google.re2j.Pattern;
import google.registry.model.contact.ContactResource;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.VKey;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.request.Action;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.auth.Auth;
import java.util.Optional;
import javax.inject.Inject;

/**
 * RDAP (new WHOIS) action for entity (contact and registrar) requests. the ICANN operational
 * profile dictates that the "handle" for registrars is to be the IANA registrar ID:
 *
 * <p>2.8.3. Registries MUST support lookup for entities with the registrar role within other
 * objects using the handle (as described in 3.1.5 of RFC 9082). The handle of the entity with the
 * registrar role MUST be equal to IANA Registrar ID. The entity with the registrar role in the RDAP
 * response MUST contain a publicIDs member to identify the IANA Registrar ID from the IANA’s
 * Registrar ID registry. The type value of the publicID object MUST be equal to IANA Registrar ID.
 */
@Action(
    service = Action.Service.PUBAPI,
    path = "/rdap/entity/",
    method = {GET, HEAD},
    isPrefix = true,
    auth = Auth.AUTH_PUBLIC)
public class RdapEntityAction extends RdapActionBase {

  private static final Pattern ROID_PATTERN = Pattern.compile("[-_.a-zA-Z0-9]+");

  @Inject public RdapEntityAction() {
    super("entity", EndpointType.ENTITY);
  }

  @Override
  public RdapEntity getJsonObjectForResource(
      String pathSearchString, boolean isHeadRequest) {
    // The query string is not used; the RDAP syntax is /rdap/entity/handle (the handle is the roid
    // for contacts and the client identifier/fn for registrars). Since RDAP's concept of an entity
    // includes both contacts and registrars, search for one first, then the other.

    // RDAP Technical Implementation Guide 2.3.1 - MUST support contact entity lookup using the
    // handle
    if (ROID_PATTERN.matcher(pathSearchString).matches()) {
      VKey<ContactResource> contactVKey = VKey.create(ContactResource.class, pathSearchString);
      Optional<ContactResource> contactResource =
          transactIfJpaTm(() -> tm().loadByKeyIfPresent(contactVKey));
      // As per Andy Newton on the regext mailing list, contacts by themselves have no role, since
      // they are global, and might have different roles for different domains.
      if (contactResource.isPresent() && isAuthorized(contactResource.get())) {
        return rdapJsonFormatter.createRdapContactEntity(
            contactResource.get(), ImmutableSet.of(), OutputDataType.FULL);
      }
    }

    // RDAP Technical Implementation Guide 2.4.1 - MUST support registrar entity lookup using the
    // IANA ID as handle
    Long ianaIdentifier = Longs.tryParse(pathSearchString);
    if (ianaIdentifier != null) {
      Optional<Registrar> registrar = getRegistrarByIanaIdentifier(ianaIdentifier);
      if (registrar.isPresent() && isAuthorized(registrar.get())) {
        return rdapJsonFormatter.createRdapRegistrarEntity(registrar.get(), OutputDataType.FULL);
      }
    }

    // RDAP Technical Implementation Guide 2.4.2 - MUST support registrar entity lookup using the
    // fn as handle
    Optional<Registrar> registrar = getRegistrarByName(pathSearchString);
    if (registrar.isPresent() && isAuthorized(registrar.get())) {
      return rdapJsonFormatter.createRdapRegistrarEntity(registrar.get(), OutputDataType.FULL);
    }

    // At this point, we have failed to find either a contact or a registrar.
    //
    // RFC7480 5.3 - if the server wishes to respond that it doesn't have data satisfying the
    // query, it MUST reply with 404 response code.
    //
    // Note we don't do RFC7480 5.3 - returning a different code if we wish to say "this info
    // exists but we don't want to show it to you", because we DON'T wish to say that.
    throw new NotFoundException(pathSearchString + " not found");
  }
}
