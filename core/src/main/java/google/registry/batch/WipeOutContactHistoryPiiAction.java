// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.contact.ContactHistory;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import org.hibernate.CacheMode;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.query.Query;

@Action(
    service = Service.BACKEND,
    path = WipeOutContactHistoryPiiAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
/**
 * An action that wipes out the pii fields of ContactHistory entities that are older than a certain
 * amount of time. This periodic wipe out action only applies to SQL.
 */
public class WipeOutContactHistoryPiiAction implements Runnable {
  public static final String PATH = "/_dr/task/wipeOutContactHistoryPii";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final Clock clock;
  private final Response response;
  private final int minMonthsBeforeWipeOut;
  private final int wipeOutQueryBatchSize;

  @Inject
  public WipeOutContactHistoryPiiAction(
      Clock clock,
      @Config("minMonthsBeforeWipeOut") int minMonthsBeforeWipeOut,
      @Config("wipeOutQueryBatchSize") int wipeOutQueryBatchSize,
      Response response) {
    this.clock = clock;
    this.response = response;
    this.minMonthsBeforeWipeOut = minMonthsBeforeWipeOut;
    this.wipeOutQueryBatchSize = wipeOutQueryBatchSize;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    try {
      processData(getAllHistoryEntriesOlderThan(minMonthsBeforeWipeOut));
      response.setStatus(SC_OK);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Exception thrown when wiping out contact history pii");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(
          String.format("Exception thrown when wiping out contact history pii with cause: %s", e));
    }
  }
  /** Returns a list of ContactHistory entities that are older than @param numOfMonths from now. */
  @VisibleForTesting
  ScrollableResults getAllHistoryEntriesOlderThan(int numOfMonths) {
    return jpaTm()
        // email is one of the fields that indicate if the pii data of a ContactHistory entity
        // have been wiped already. Refer to RFC 5733 for more information.
        .query(
            "FROM ContactHistory WHERE modificationTime < :numOfMonthsFromNow "
                + "AND email IS NOT NULL ORDER BY modificationTime ASC",
            ContactHistory.class)
        .setParameter("numOfMonthsFromNow", clock.nowUtc().minusMonths(numOfMonths))
        .unwrap(Query.class)
        .setCacheMode(CacheMode.IGNORE)
        .setFetchSize(wipeOutQueryBatchSize)
        .scroll(ScrollMode.FORWARD_ONLY);
  }

  @VisibleForTesting
  int processData(ScrollableResults oldContactHistoryData) {
    AtomicInteger numOfProcessedEntities = new AtomicInteger();
    ImmutableList.Builder<ContactHistory> contactHistoryBatchBuilder =
        new ImmutableList.Builder<>();
    for (int i = 1; oldContactHistoryData.next(); i = (i + 1) % wipeOutQueryBatchSize) {
      contactHistoryBatchBuilder.add((ContactHistory) oldContactHistoryData.get(0));
      if (i == 0) {
        ImmutableList<ContactHistory> fullBatchOfContactHistoryData =
            ImmutableList.copyOf(contactHistoryBatchBuilder.build());
        jpaTm()
            .transact(
                () -> {
                  wipeOutContactHistoryPii(fullBatchOfContactHistoryData);
                  logger.atFine().atMostEvery(2, TimeUnit.SECONDS).log(
                      "Wiped out a full batch of data with %d contact history entities",
                      fullBatchOfContactHistoryData.size());
                  numOfProcessedEntities.addAndGet(wipeOutQueryBatchSize);
                });
        // DB flush and session clearing to avoid OOM
        jpaTm().getEntityManager().flush();
        jpaTm().getEntityManager().clear();
        // reset contact history batch after the transaction
        contactHistoryBatchBuilder = new ImmutableList.Builder<>();
      }
    }
    // process the last batch of contact history entities where there's less than full batch of data
    ImmutableList<ContactHistory> lastBatchOfData = contactHistoryBatchBuilder.build();
    jpaTm().transact(() -> wipeOutContactHistoryPii(lastBatchOfData));
    logger.atFine().atMostEvery(2, TimeUnit.SECONDS).log(
        "Wiped out the last batch of data with %d contact history entities",
        lastBatchOfData.size());
    numOfProcessedEntities.addAndGet(lastBatchOfData.size());
    logger.atInfo().log(
        "Wiped out pii fields of %d ContactHistory entities", numOfProcessedEntities.get());
    return numOfProcessedEntities.get();
  }

  /** Wipes out the Pii of each of the contact history entities in the list. */
  @VisibleForTesting
  void wipeOutContactHistoryPii(ImmutableList<ContactHistory> contactHistoryData) {
    for (ContactHistory contactHistory : contactHistoryData) {
      jpaTm().update(contactHistory.asBuilder().wipeOutPii().build());
    }
  }
}
