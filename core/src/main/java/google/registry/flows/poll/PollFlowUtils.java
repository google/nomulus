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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.googlecode.objectify.cmd.Query;
import google.registry.model.poll.PollMessage;
import org.joda.time.DateTime;

/** Static utility functions for poll flows. */
public final class PollFlowUtils {

  private PollFlowUtils() {}

  /** Returns a query for poll messages for the logged in registrar which are not in the future. */
  public static Query<PollMessage> getPollMessagesQuery(String clientId, DateTime now) {
    return ofy().load()
        .type(PollMessage.class)
        .filter("clientId", clientId)
        .filter("eventTime <=", now.toDate())
        .order("eventTime");
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
        tm().saveNewOrUpdate(autorenewPollMessage.asBuilder().setEventTime(nextEventTime).build());
        includeAckedMessageInCount = isBeforeOrAt(nextEventTime, tm().getTransactionTime());
      } else {
        tm().delete(autorenewPollMessage.createVKey());
      }
    } else {
      throw new IllegalArgumentException("Unknown poll message type: " + pollMessage.getClass());
    }
    return includeAckedMessageInCount;
  }
}
