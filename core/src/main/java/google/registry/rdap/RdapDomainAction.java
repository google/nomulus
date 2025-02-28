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

import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.model.domain.Domain;
import google.registry.model.tld.Tld;
import google.registry.rdap.RdapJsonFormatter.OutputDataType;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapObjectClasses.RdapDomain;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.auth.Auth;
import java.util.Optional;
import javax.inject.Inject;

/** RDAP (new WHOIS) action for domain requests. */
@Action(
    service = GaeService.PUBAPI,
    path = "/rdap/domain/",
    method = {GET, HEAD},
    isPrefix = true,
    auth = Auth.AUTH_PUBLIC)
public class RdapDomainAction extends RdapActionBase {

  @Inject public RdapDomainAction() {
    super("domain name", EndpointType.DOMAIN);
  }

  @Override
  public RdapDomain getJsonObjectForResource(String pathSearchString, boolean isHeadRequest) {
    // RDAP Technical Implementation Guide 2.1.1 - we must support A-label (Punycode) and U-label
    // (Unicode) formats. canonicalizeName will transform Unicode to Punycode so we support both.
    pathSearchString = canonicalizeName(pathSearchString);
    InternetDomainName domainName;
    try {
      domainName = validateDomainName(pathSearchString);
    } catch (EppException e) {
      throw new BadRequestException(
          String.format(
              "%s is not a valid %s: %s",
              pathSearchString, getHumanReadableObjectTypeName(), e.getMessage()));
    }
    // The query string is not used; the RDAP syntax is /rdap/domain/mydomain.com.
    Optional<Domain> domain =
        loadByForeignKeyCached(
            Domain.class,
            pathSearchString,
            shouldIncludeDeleted() ? START_OF_TIME : rdapJsonFormatter.getRequestTime());
    if (domain.isEmpty() || !isAuthorized(domain.get())) {
      handlePossibleBsaBlock(domainName);
      // RFC7480 5.3 - if the server wishes to respond that it doesn't have data satisfying the
      // query, it MUST reply with 404 response code.
      //
      // Note we don't do RFC7480 5.3 - returning a different code if we wish to say "this info
      // exists but we don't want to show it to you", because we DON'T wish to say that.
      throw new NotFoundException(pathSearchString + " not found");
    }
    return rdapJsonFormatter.createRdapDomain(domain.get(), OutputDataType.FULL);
  }

  private void handlePossibleBsaBlock(InternetDomainName domainName) {
    Tld tld = Tld.get(domainName.parent().toString());
    if (DomainFlowUtils.isBlockedByBsa(domainName.parts().getFirst(), tld, clock.nowUtc())) {
      throw new DomainBlockedByBsaException(domainName + " blocked by BSA");
    }
  }

  static class DomainBlockedByBsaException extends RuntimeException {
    DomainBlockedByBsaException(String message) {
      super(message);
    }
  }
}
