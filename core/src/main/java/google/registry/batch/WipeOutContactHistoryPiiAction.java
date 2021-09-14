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
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.contact.ContactHistory;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.persistence.TypedQuery;
import org.hibernate.CacheMode;
import org.hibernate.query.Query;

/**
 * An action that wipes out Personal Identifiable Information (PII) fields of {@link ContactHistory}
 * entities.
 *
 * <p>ContactHistory entities should be retained in the database for only certain amount of time.
 * This periodic wipe out action only applies to SQL.
 */
@Action(
    service = Service.BACKEND,
    path = WipeOutContactHistoryPiiAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class WipeOutContactHistoryPiiAction implements Runnable {

  public static final String PATH = "/_dr/task/wipeOutContactHistoryPii";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final Clock clock;
  private final Response response;
  private final int minMonthsBeforeWipeOut;
  private final int wipeOutQueryBatchSize;
  private final int numOfEntitiesToWipeOutPerTransaction;

  @Inject
  public WipeOutContactHistoryPiiAction(
      Clock clock,
      @Config("minMonthsBeforeWipeOut") int minMonthsBeforeWipeOut,
      @Config("wipeOutQueryBatchSize") int wipeOutQueryBatchSize,
      @Config("numOfEntitiesToWipeOutPerTransaction") int numOfEntitiesToWipeOutPerTransaction,
      Response response) {
    this.clock = clock;
    this.response = response;
    this.minMonthsBeforeWipeOut = minMonthsBeforeWipeOut;
    // the number of entities to buffer in memory
    this.wipeOutQueryBatchSize = wipeOutQueryBatchSize;
    // the maximum returned number of entities per transaction
    this.numOfEntitiesToWipeOutPerTransaction = numOfEntitiesToWipeOutPerTransaction;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    int totalNumOfProcessedEntities = 0;
    try {
      while (true) {
        int numOfProcessedEntities =
            jpaTm()
                .transact(
                    () ->
                        wipeOutContactHistoryData(
                            getAllHistoryEntitiesOlderThan(minMonthsBeforeWipeOut)));
        totalNumOfProcessedEntities += numOfProcessedEntities;
        // If the set of data contains less than the default number of entities to process, it means
        // the last set of data has been processed.
        if (numOfProcessedEntities < numOfEntitiesToWipeOutPerTransaction) {
          break;
        }
      }
      logger.atInfo().log(
          "Processed total number of %d ContactHistory entities.", totalNumOfProcessedEntities);
      response.setStatus(SC_OK);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          "Exception thrown during the process of wiping out contact history PII.");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(
          String.format(
              "Exception thrown during the process of wiping out contact history PII with cause"
                  + ": %s",
              e));
    }
  }

  /**
   * Returns a stream of up to {@link #numOfEntitiesToWipeOutPerTransaction} {@link ContactHistory}
   * entities that are older than @param numOfMonths from now.
   */
  @VisibleForTesting
  Stream<ContactHistory> getAllHistoryEntitiesOlderThan(int numOfMonths) {
    // Email is one of the required fields in EPP, meaning it's initially not null.
    // Therefore, checking if it's null is one way to avoid processing contact history entities
    // that have been processed previously. Refer to RFC 5733 for more information.
    TypedQuery<ContactHistory> query =
        jpaTm()
            .query(
                "FROM ContactHistory WHERE modificationTime < :wipeOutTime "
                    + "AND email IS NOT NULL ORDER BY modificationTime ASC",
                ContactHistory.class)
            .setParameter("wipeOutTime", clock.nowUtc().minusMonths(numOfMonths));
    query
        .unwrap(Query.class)
        .setCacheMode(CacheMode.IGNORE)
        .setFetchSize(wipeOutQueryBatchSize)
        .setMaxResults(numOfEntitiesToWipeOutPerTransaction);
    return query.getResultStream();
  }

  /** Wipes out the PII of each of the {@link ContactHistory} entities of the stream. */
  @VisibleForTesting
  int wipeOutContactHistoryData(Stream<ContactHistory> contactHistoryEntities) {
    AtomicInteger numOfEntities = new AtomicInteger(0);
    contactHistoryEntities.forEach(
        contactHistoryEntity -> {
          jpaTm().update(contactHistoryEntity.asBuilder().wipeOutPii().build());
          numOfEntities.incrementAndGet();
        });
    logger.atInfo().log(
        "Wiped out all PII fields of %d ContactHistory entities.", numOfEntities.get());
    return numOfEntities.get();
  }
}
