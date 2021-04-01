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

package google.registry.flows.poll;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.googlecode.objectify.cmd.Query;
import google.registry.model.poll.PollMessage;
import java.util.Optional;
import org.joda.time.DateTime;

/** Static utility functions for poll flows. */
public final class PollFlowUtils {

  public static final String SQL_POLL_MESSAGE_QUERY =
      "FROM PollMessage WHERE clientId = :registrarId AND eventTime <= :now ORDER BY eventTime ASC";
  private static final String SQL_POLL_MESSAGE_COUNT_QUERY =
      "SELECT COUNT(*) FROM PollMessage WHERE clientId = :registrarId AND eventTime <= :now";

  /** Returns the number of poll messages for the given registrar that are not in the future. */
  public static int getPollMessageCount(String registrarId, DateTime now) {
    if (tm().isOfy()) {
      return datastorePollMessageQuery(registrarId, now).count();
    } else {
      return jpaTm()
          .transact(
              () ->
                  jpaTm()
                      .query(SQL_POLL_MESSAGE_COUNT_QUERY, Long.class)
                      .setParameter("registrarId", registrarId)
                      .setParameter("now", now)
                      .getSingleResult()
                      .intValue());
    }
  }

  /** Returns the first (by event time) poll message not in the future for this registrar. */
  public static Optional<PollMessage> getFirstPollMessage(String registrarId, DateTime now) {
    if (tm().isOfy()) {
      return Optional.ofNullable(datastorePollMessageQuery(registrarId, now).first().now());
    } else {
      return jpaTm()
          .transact(
              () ->
                  jpaTm()
                      .query(SQL_POLL_MESSAGE_QUERY, PollMessage.class)
                      .setParameter("registrarId", registrarId)
                      .setParameter("now", now)
                      .setMaxResults(1)
                      .getResultStream()
                      .findFirst());
    }
  }

  /**
   * Acknowledges the given {@link PollMessage} and returns whether we should include the current
   * acked message in the updated message count that's returned to the user.
   *
   * <p>The only case where we do so is if an autorenew poll message is acked, but its next event is
   * already ready to be delivered.
   */
  public static boolean ackPollMessage(PollMessage pollMessage) {
    checkArgument(
        isBeforeOrAt(pollMessage.getEventTime(), tm().getTransactionTime()),
        "Cannot ACK poll message with ID %s because its event time is in the future: %s",
        pollMessage.getId(),
        pollMessage.getEventTime());
    boolean includeAckedMessageInCount = false;
    if (pollMessage instanceof PollMessage.OneTime) {
      // One-time poll messages are deleted once acked.
      tm().delete(pollMessage.createVKey());
    } else if (pollMessage instanceof PollMessage.Autorenew) {
      PollMessage.Autorenew autorenewPollMessage = (PollMessage.Autorenew) pollMessage;

      // Move the eventTime of this autorenew poll message forward by a year.
      DateTime nextEventTime = autorenewPollMessage.getEventTime().plusYears(1);

      // If the next event falls within the bounds of the end time, then just update the eventTime
      // and re-save it for future autorenew poll messages to be delivered. Otherwise, this
      // autorenew poll message has no more events to deliver and should be deleted.
      if (nextEventTime.isBefore(autorenewPollMessage.getAutorenewEndTime())) {
        tm().put(autorenewPollMessage.asBuilder().setEventTime(nextEventTime).build());
        includeAckedMessageInCount = isBeforeOrAt(nextEventTime, tm().getTransactionTime());
      } else {
        tm().delete(autorenewPollMessage.createVKey());
      }
    } else {
      throw new IllegalArgumentException("Unknown poll message type: " + pollMessage.getClass());
    }
    return includeAckedMessageInCount;
  }

  /** A Datastore query for poll messages from the given registrar that are not in the future. */
  public static Query<PollMessage> datastorePollMessageQuery(String registrarId, DateTime now) {
    return ofy()
        .load()
        .type(PollMessage.class)
        .filter("clientId", registrarId)
        .filter("eventTime <=", now.toDate())
        .order("eventTime");
  }

  private PollFlowUtils() {}
}
