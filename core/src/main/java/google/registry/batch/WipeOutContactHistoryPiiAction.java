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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;

@Action(
    service = Service.BACKEND,
    path = WipeOutContactHistoryPiiAction.PATH,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
/**
 * An action that removes the fields on contact entities in history entries that have been stored
 * for a certain period of time. This periodic wipe out action only applies to SQL.
 */
public class WipeOutContactHistoryPiiAction implements Runnable {
  public static final String PATH = "/_dr/task/wipeOutContactHistoryPii";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final Clock clock;
  private final Response response;
  private final int minMonthsBeforeWipeOut;

  @Inject
  public WipeOutContactHistoryPiiAction(
      Clock clock,
      @Config("minMonthsBeforeWipeOut") int minMonthsBeforeWipeOut,
      Response response) {
    this.clock = clock;
    this.response = response;
    this.minMonthsBeforeWipeOut = minMonthsBeforeWipeOut;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    try {
      for (ContactHistory contactHistory : getAllHistoryEntriesOlderThan(minMonthsBeforeWipeOut)) {
        wipeOutContactHistoryPii(contactHistory);
      }
      response.setStatus(SC_OK);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log("Exception thrown when wiping out contact history "
          + "pii");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(
          String.format("Exception thrown when wiping out contact history pii with cause: %s", e));
    }
  }
  /** Returns a list of ContactHistory entities that are @param numOfMonths from now. */
  @VisibleForTesting
  List<ContactHistory> getAllHistoryEntriesOlderThan(int numOfMonths) {
    if (!tm().isOfy()) {
      List<ContactHistory> resultList =
          jpaTm()
              .transact(
                  () ->
                      jpaTm()
                          .query(
                              "FROM ContactHistory WHERE modificationTime < :date "
                                  + "ORDER BY modificationTime ASC",
                              ContactHistory.class)
                          .setParameter("date", clock.nowUtc().minusMonths(numOfMonths))
                          .getResultList());
      logger.atInfo().log(
          "The following list contains the modification time(s) of contact history entities "
              + "that are older than %d month(s): %s.",
          numOfMonths,
          resultList.stream()
              .map(ContactHistory::getModificationTime)
              .collect(Collectors.toList())
              .toString());
      return resultList;
    }
    return Collections.emptyList();
  }
  /** Wipes out the Pii of a contact history entry and updates the record in the database. */
  @VisibleForTesting
  void wipeOutContactHistoryPii(ContactHistory contactHistory) {
    if (!tm().isOfy()) {
      jpaTm().transact(() -> jpaTm().update(contactHistory.asBuilder().wipeOutPii().build()));
    }
  }
}
