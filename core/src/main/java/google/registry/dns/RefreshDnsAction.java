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

package google.registry.dns;

import static google.registry.dns.DnsUtils.requestDomainDnsRefresh;
import static google.registry.dns.DnsUtils.requestHostDnsRefresh;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.dns.DnsUtils.TargetType;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotFoundException;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import jakarta.inject.Inject;

/** Action that manually triggers refresh of DNS information. */
@Action(
    service = GaeService.BACKEND,
    path = "/_dr/task/dnsRefresh",
    automaticallyPrintOk = true,
    auth = Auth.AUTH_ADMIN)
public final class RefreshDnsAction implements Runnable {

  private final Clock clock;
  private final String domainOrHostName;
  private final TargetType type;

  @Inject
  RefreshDnsAction(
      @Parameter("domainOrHostName") String domainOrHostName,
      @Parameter("type") TargetType type,
      Clock clock) {
    this.domainOrHostName = domainOrHostName;
    this.type = type;
    this.clock = clock;
  }

  @Override
  public void run() {
    if (!domainOrHostName.contains(".")) {
      throw new BadRequestException("URL parameter 'name' must be fully qualified");
    }
    tm().transact(
            () -> {
              switch (type) {
                case DOMAIN -> {
                  loadAndVerifyExistence(Domain.class, domainOrHostName);
                  requestDomainDnsRefresh(domainOrHostName);
                }
                case HOST -> {
                  verifyHostIsSubordinate(loadAndVerifyExistence(Host.class, domainOrHostName));
                  requestHostDnsRefresh(domainOrHostName);
                }
                default -> throw new BadRequestException("Unsupported type: " + type);
              }
            });
  }

  private <T extends EppResource & ForeignKeyedEppResource>
      T loadAndVerifyExistence(Class<T> clazz, String foreignKey) {
    return loadByForeignKey(clazz, foreignKey, clock.nowUtc())
        .orElseThrow(
            () ->
                new NotFoundException(
                    String.format(
                        "%s %s not found",
                        clazz.getAnnotation(ExternalMessagingName.class).value(),
                        domainOrHostName)));
  }

  private static void verifyHostIsSubordinate(Host host) {
    if (!host.isSubordinate()) {
      throw new BadRequestException(
          String.format("%s isn't a subordinate hostname", host.getHostName()));
    }
  }
}
