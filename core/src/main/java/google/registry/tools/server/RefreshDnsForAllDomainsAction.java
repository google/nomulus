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
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.RequestParameters.PARAM_BATCH_SIZE;
import static google.registry.request.RequestParameters.PARAM_TLDS;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import jakarta.persistence.TypedQuery;
import java.util.Optional;
import java.util.Random;
import javax.annotation.Nullable;
import org.apache.arrow.util.VisibleForTesting;
import org.apache.http.HttpStatus;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * A task that enqueues DNS publish tasks on all active domains on the specified TLD(s).
 *
 * <p>This refreshes DNS both for all domain names and all in-bailiwick hostnames, as DNS writers
 * are responsible for enqueuing refresh tasks for subordinate hosts. So this action thus refreshes
 * DNS for everything applicable under all TLDs under management.
 *
 * <p>You may pass in a {@code batchSize} for the batched read of domains from the database. This is
 * recommended to be somewhere between 200 and 500. The default value is 250.
 *
 * <p>If {@code activeOrDeletedSince} is passed in the request, this action will enqueue DNS publish
 * tasks on all domains with a deletion time equal or greater than the value provided, including
 * domains that have since been deleted.
 */
@Action(
    service = GaeService.TOOLS,
    path = "/_dr/task/refreshDnsForAllDomains",
    auth = Auth.AUTH_ADMIN)
public class RefreshDnsForAllDomainsAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** The number of DNS updates to enqueue per transaction. */
  private static final int DEFAULT_BATCH_SIZE = 250;

  /**
   * The default number of DNS updates it is safe to execute per minute.
   *
   * <p>This is mostly a guess based on existing system performance, but the point is to be on the
   * safe side and not cause contention with ongoing DNS updates from clients.
   */
  private static final int DEFAULT_REFRESH_QPS = 7;

  private final Response response;
  private final ImmutableSet<String> tlds;

  // Recommended value for batch size is between 200 and 500
  private final int batchSize;

  private final int refreshQps;

  private final Random random;

  private final DateTime activeOrDeletedSince;

  @Inject
  RefreshDnsForAllDomainsAction(
      Response response,
      @Parameter(PARAM_TLDS) ImmutableSet<String> tlds,
      @Parameter(PARAM_BATCH_SIZE) Optional<Integer> batchSize,
      @Parameter("refreshQps") Optional<Integer> refreshQps,
      @Parameter("activeOrDeletedSince") Optional<DateTime> activeOrDeletedSince,
      Random random) {
    this.response = response;
    this.tlds = tlds;
    this.batchSize = batchSize.orElse(DEFAULT_BATCH_SIZE);
    this.refreshQps = refreshQps.orElse(DEFAULT_REFRESH_QPS);
    this.activeOrDeletedSince = activeOrDeletedSince.orElse(END_OF_TIME);
    this.random = random;
  }

  @Override
  public void run() {
    assertTldsExist(tlds);
    checkArgument(batchSize > 0, "Must specify a positive number for batch size");
    logger.atInfo().log("Enqueueing DNS refresh tasks for TLDs %s.", tlds);
    Duration smear = tm().transact(TRANSACTION_REPEATABLE_READ, this::calculateSmear);

    ImmutableList<String> domainsBatch;
    @Nullable String lastInPreviousBatch = null;
    do {
      Optional<String> lastInPreviousBatchOpt = Optional.ofNullable(lastInPreviousBatch);
      domainsBatch =
          tm().transact(
                  TRANSACTION_REPEATABLE_READ, () -> refreshBatch(lastInPreviousBatchOpt, smear));
      lastInPreviousBatch = domainsBatch.isEmpty() ? null : getLast(domainsBatch);
    } while (domainsBatch.size() == batchSize);
    logger.atInfo().log("Finished enqueueing DNS refresh tasks.");
  }

  /**
   * Calculates the smear duration to enqueue refreshes so that the DNS queue does not get
   * overloaded.
   */
  private Duration calculateSmear() {
    Long activeDomains =
        tm().query(
                "SELECT COUNT(*) FROM Domain WHERE tld IN (:tlds) AND deletionTime >="
                    + " :activeOrDeletedSince",
                Long.class)
            .setParameter("tlds", tlds)
            .setParameter("activeOrDeletedSince", activeOrDeletedSince)
            .getSingleResult();
    Duration smear = Duration.standardSeconds(Math.max(activeDomains / refreshQps, 1));
    logger.atInfo().log("Smearing %d domain DNS refresh tasks across %s.", activeDomains, smear);
    return smear;
  }

  private ImmutableList<String> getBatch(Optional<String> lastInPreviousBatch) {
    String sql =
        String.format(
            "SELECT domainName FROM Domain WHERE tld IN (:tlds) AND"
                + " deletionTime >= :activeOrDeletedSince %s ORDER BY domainName ASC",
            lastInPreviousBatch.isPresent() ? "AND domainName > :lastInPreviousBatch" : "");
    TypedQuery<String> query =
        tm().query(sql, String.class)
            .setParameter("tlds", tlds)
            .setParameter("activeOrDeletedSince", activeOrDeletedSince);
    lastInPreviousBatch.ifPresent(l -> query.setParameter("lastInPreviousBatch", l));
    return query.setMaxResults(batchSize).getResultStream().collect(toImmutableList());
  }

  @VisibleForTesting
  ImmutableList<String> refreshBatch(Optional<String> lastInPreviousBatch, Duration smear) {
    ImmutableList<String> domainBatch = getBatch(lastInPreviousBatch);
    try {
      // Smear the task execution time over the next N seconds.
      requestDomainDnsRefresh(
          domainBatch, Duration.standardSeconds(random.nextInt((int) smear.getStandardSeconds())));
    } catch (Throwable t) {
      logger.atSevere().withCause(t).log("Error while enqueuing DNS refresh batch");
      response.setStatus(HttpStatus.SC_OK);
    }
    return domainBatch;
  }
}
