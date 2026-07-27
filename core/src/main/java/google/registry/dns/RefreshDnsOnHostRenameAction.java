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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.dns.DnsUtils.requestDomainDnsRefresh;
import static google.registry.dns.RefreshDnsOnHostRenameAction.PATH;
import static google.registry.model.EppResourceUtils.getLinkedDomainKeys;
import static google.registry.model.EppResourceUtils.isDeleted;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.net.MediaType;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

@Action(
    service = Action.Service.BACKEND,
    path = PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_ADMIN)
public class RefreshDnsOnHostRenameAction implements Runnable {

  public static final String QUEUE_HOST_RENAME = "async-host-rename";
  public static final String PARAM_HOST_KEY = "hostKey";
  public static final String PATH = "/_dr/task/refreshDnsOnHostRename";

  private static final int DNS_REFRESH_BATCH_SIZE = 1000;

  private final VKey<Host> hostKey;
  private final Response response;

  @Inject
  RefreshDnsOnHostRenameAction(@Parameter(PARAM_HOST_KEY) String hostKey, Response response) {
    this.hostKey = VKey.createEppVKeyFromString(hostKey);
    this.response = response;
  }

  @Override
  public void run() {
    try {
      runDnsRefresh();
      response.setStatus(SC_OK);
    } catch (RefreshDnsNonRetryableException e) {
      // Set the response status code to be 204 so to not retry.
      response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
      response.setStatus(SC_NO_CONTENT);
      response.setPayload(e.getMessage());
    }
  }

  private void runDnsRefresh() {
    ImmutableSet<VKey<Domain>> linkedDomainKeys =
        tm().transact(
                () -> {
                  Instant now = tm().getTxTime();
                  Host host =
                      tm().loadByKeyIfPresent(hostKey)
                          .orElseThrow(
                              () ->
                                  new RefreshDnsNonRetryableException(
                                      String.format(
                                          "Host to refresh does not exist: %s", hostKey)));
                  if (isDeleted(host, now)) {
                    throw new RefreshDnsNonRetryableException(
                        String.format(
                            "Host to refresh is already deleted: %s", host.getHostName()));
                  }
                  return getLinkedDomainKeys(
                      hostKey, host.getUpdateTimestamp().getTimestamp(), null);
                });
    for (List<VKey<Domain>> batch : Iterables.partition(linkedDomainKeys, DNS_REFRESH_BATCH_SIZE)) {
      tm().transact(
              () -> {
                ImmutableSet<String> domainNames =
                    tm().loadByKeysIfPresent(batch).values().stream()
                        .filter(Domain::shouldPublishToDns)
                        .map(Domain::getDomainName)
                        .collect(toImmutableSet());
                requestDomainDnsRefresh(domainNames);
              });
    }
  }

  private static class RefreshDnsNonRetryableException extends RuntimeException {
    private RefreshDnsNonRetryableException(String message) {
      super(message);
    }
  }
}
