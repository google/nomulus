// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.dns.RefreshDnsOnHostRenameAction.PATH;
import static google.registry.model.EppResourceUtils.getLinkedDomainKeys;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;

import com.google.common.net.MediaType;
import google.registry.model.EppResourceUtils;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import org.joda.time.DateTime;

@Action(
    service = GaeService.BACKEND,
    path = PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_ADMIN)
public class RefreshDnsOnHostRenameAction implements Runnable {

  public static final String QUEUE_HOST_RENAME = "async-host-rename";
  public static final String PARAM_HOST_KEY = "hostKey";
  public static final String PATH = "/_dr/task/refreshDnsOnHostRename";

  private final VKey<Host> hostKey;
  private final Response response;

  @Inject
  RefreshDnsOnHostRenameAction(@Parameter(PARAM_HOST_KEY) String hostKey, Response response) {
    this.hostKey = VKey.createEppVKeyFromString(hostKey);
    this.response = response;
  }

  @Override
  public void run() {
    tm().transact(
            () -> {
              DateTime now = tm().getTransactionTime();
              Host host = tm().loadByKeyIfPresent(hostKey).orElse(null);
              boolean hostValid = true;
              String failureMessage = null;
              if (host == null) {
                hostValid = false;
                failureMessage = String.format("Host to refresh does not exist: %s", hostKey);
              } else if (EppResourceUtils.isDeleted(host, now)) {
                hostValid = false;
                failureMessage =
                    String.format("Host to refresh is already deleted: %s", host.getHostName());
              } else {
                getLinkedDomainKeys(
                        host.createVKey(), host.getUpdateTimestamp().getTimestamp(), null)
                    .stream()
                    .map(domainKey -> tm().loadByKey(domainKey))
                    .filter(Domain::shouldPublishToDns)
                    .forEach(domain -> requestDomainDnsRefresh(domain.getDomainName()));
              }

              if (!hostValid) {
                // Set the response status code to be 204 so to not retry.
                response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
                response.setStatus(SC_NO_CONTENT);
                response.setPayload(failureMessage);
              }
            });
  }
}
