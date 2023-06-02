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

package google.registry.tools.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static google.registry.dns.DnsUtils.requestDomainDnsRefresh;
import static google.registry.model.tld.Tlds.assertTldsExist;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.RequestParameters.PARAM_TLDS;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import java.util.Random;
import javax.inject.Inject;
import org.apache.http.HttpStatus;
import org.joda.time.Duration;

/**
 * A task that enqueues DNS publish tasks on all active domains on the specified TLD(s).
 *
 * <p>This refreshes DNS both for all domain names and all in-bailiwick hostnames, as DNS writers
 * are responsible for enqueuing refresh tasks for subordinate hosts. So this action thus refreshes
 * DNS for everything applicable under all TLDs under management.
 *
 * <p>Because there are no auth settings in the {@link Action} annotation, this command can only be
 * run internally, or by pretending to be internal by setting the X-AppEngine-QueueName header,
 * which only admin users can do.
 *
 * <p>You must pass in a number of {@code smearMinutes} as a URL parameter so that the DNS queue
 * doesn't get overloaded. A rough rule of thumb for Cloud DNS is 1 minute per every 1,000 domains.
 * This smears the updates out over the next N minutes. For small TLDs consisting of fewer than
 * 1,000 domains, passing in 1 is fine (which will execute all the updates immediately).
 *
 * <p>You may pass in a {@code batchSize} for the batched read of domains from the database. This is
 * recommended to be somewhere between 200 and 500. The default value is 250.
 */
@Action(
    service = Action.Service.TOOLS,
    path = "/_dr/task/refreshDnsForAllDomains",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class RefreshDnsForAllDomainsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Response response;
  private final ImmutableSet<String> tlds;

  // Recommended value for batch size is between 200 and 500
  private final int batchSize;
  private final int smearMinutes;
  private final Random random;

  @Inject
  RefreshDnsForAllDomainsAction(
      Response response,
      @Parameter(PARAM_TLDS) ImmutableSet<String> tlds,
      @Parameter("batchSize") int batchSize,
      @Parameter("smearMinutes") int smearMinutes,
      Random random) {
    this.response = response;
    this.tlds = tlds;
    this.batchSize = batchSize;
    this.smearMinutes = smearMinutes;
    this.random = random;
  }

  @Override
  public void run() {
    assertTldsExist(tlds);
    checkArgument(smearMinutes > 0, "Must specify a positive number of smear minutes");
    checkArgument(batchSize > 0, "Must specify a positive number for batch size");
    ImmutableList<String> previousBatch = ImmutableList.of("");
    do {
      // This temp variable is required since Java requires that an effectively final variable is
      // used in the lambda expression below
      ImmutableList<String> finalPreviousBatch = previousBatch;
      previousBatch = tm().transact(() -> refreshBatch(ImmutableList.copyOf(finalPreviousBatch)));
    } while (previousBatch.size() == batchSize);
  }

  private ImmutableList<String> getBatch(ImmutableList<String> previousBatch) {
    return tm().query(
            "SELECT domainName FROM Domain WHERE tld IN (:tlds) AND"
                + " deletionTime = :endOfTime  AND domainName >"
                + " :lastInPreviousBatch ORDER BY domainName ASC",
            String.class)
        .setParameter("tlds", tlds)
        .setParameter("endOfTime", END_OF_TIME)
        .setParameter("lastInPreviousBatch", getLast(previousBatch))
        .setMaxResults(batchSize)
        .getResultStream()
        .collect(toImmutableList());
  }

  private ImmutableList<String> refreshBatch(ImmutableList<String> previousBatch) {
    ImmutableList<String> domainBatch = getBatch(previousBatch);
    try {
      // Smear the task execution time over the next N minutes.
      requestDomainDnsRefresh(domainBatch, Duration.standardMinutes(random.nextInt(smearMinutes)));
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("Error while enqueuing DNS refresh batch");
      response.setStatus(HttpStatus.SC_OK);
    }
    return domainBatch;
  }
}
