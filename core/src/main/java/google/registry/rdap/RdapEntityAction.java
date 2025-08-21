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

import static google.registry.rdap.RdapUtils.getRegistrarByIanaIdentifier;
import static google.registry.rdap.RdapUtils.getRegistrarByName;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;

import com.google.common.primitives.Longs;
import google.registry.model.registrar.Registrar;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * RDAP action for entity (i.e. registrar) requests. the ICANN operational profile dictates that the
 * "handle" for registrars is to be the IANA registrar ID:
 *
 * <p>2.4.1.Registry RDAP servers MUST support Registrar object lookup using an entity path request
 * for entities with the registrar role using the handle (as described in 3.1.5 of RFC9082) where
 * the handle of the entity with the registrar role is be [sic] equal to the IANA Registrar ID.
 */
@Action(
    service = GaeService.PUBAPI,
    path = "/rdap/entity/",
    method = {GET, HEAD},
    isPrefix = true,
    auth = Auth.AUTH_PUBLIC)
public class RdapEntityAction extends RdapActionBase {

  @Inject public RdapEntityAction() {
    super("entity", EndpointType.ENTITY);
  }

  @Override
  public RdapEntity getJsonObjectForResource(
      String pathSearchString, boolean isHeadRequest) {
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

    // At this point, we have failed to find a registrar.
    //
    // RFC7480 5.3 - if the server wishes to respond that it doesn't have data satisfying the
    // query, it MUST reply with 404 response code.
    //
    // Note we don't do RFC7480 5.3 - returning a different code if we wish to say "this info
    // exists, but we don't want to show it to you", because we DON'T wish to say that.
    throw new NotFoundException(pathSearchString + " not found");
  }
}
