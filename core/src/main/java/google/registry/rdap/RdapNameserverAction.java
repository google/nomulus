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

import static google.registry.flows.host.HostFlowUtils.validateHostName;
import static google.registry.model.EppResourceUtils.loadByForeignKeyByCache;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import google.registry.flows.EppException;
import google.registry.model.host.Host;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapObjectClasses.RdapNameserver;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.Optional;

/** RDAP action for nameserver requests. */
@Action(
    service = GaeService.PUBAPI,
    path = "/rdap/nameserver/",
    method = {GET, HEAD},
    isPrefix = true,
    auth = Auth.AUTH_PUBLIC)
public class RdapNameserverAction extends RdapActionBase {

  @Inject public RdapNameserverAction() {
    super("nameserver", EndpointType.NAMESERVER);
  }

  @Override
  public RdapNameserver getJsonObjectForResource(String pathSearchString, boolean isHeadRequest) {
    // RDAP Technical Implementation Guide 2.1.1 - we must support A-label (Punycode) and U-label
    // (Unicode) formats. canonicalizeName will transform Unicode to Punycode so we support both.
    pathSearchString = canonicalizeName(pathSearchString);
    // The RDAP syntax is /rdap/nameserver/ns1.mydomain.com.
    try {
      validateHostName(pathSearchString);
    } catch (EppException e) {
      throw new BadRequestException(
          String.format(
              "%s is not a valid %s: %s",
              pathSearchString, getHumanReadableObjectTypeName(), e.getMessage()));
    }
    // If there are no undeleted nameservers with the given name, the foreign key should point to
    // the most recently deleted one.
    Optional<Host> host =
        loadByForeignKeyByCache(
            Host.class,
            pathSearchString,
            shouldIncludeDeleted() ? START_OF_TIME : getRequestTime());
    if (host.isEmpty() || !isAuthorized(host.get())) {
      // RFC7480 5.3 - if the server wishes to respond that it doesn't have data satisfying the
      // query, it MUST reply with 404 response code.
      //
      // Note we don't do RFC7480 5.3 - returning a different code if we wish to say "this info
      // exists but we don't want to show it to you", because we DON'T wish to say that.
      throw new NotFoundException(pathSearchString + " not found");
    }
    return rdapJsonFormatter.createRdapNameserver(host.get(), OutputDataType.FULL);
  }
}
