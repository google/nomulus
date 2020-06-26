// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.flows.poll.PollFlowUtils.getPollMessagesQuery;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.poll.PollMessageExternalKeyConverter.makePollMessageExternalId;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.QueryKeys;
import google.registry.flows.poll.PollFlowUtils;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.Autorenew;
import google.registry.model.poll.PollMessage.OneTime;
import google.registry.util.Clock;
import java.util.List;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Command to acknowledge one-time poll messages for a registrar.
 *
 * <p>This is useful to bulk ACK a large number of {@link PollMessage}s for a given registrar that
 * are gumming up that registrar's queue. ACKed poll messages are printed to stdout, so they can be
 * piped to a file and delivered to the registrar out of band if necessary. Note that the poll
 * messages are printed in an abbreviated CSV format (i.e. not the full EPP XML output) for
 * brevity's sake when dealing with many poll messages.
 *
 * <p>You may specify a string that poll messages to be ACKed should contain, which is useful if the
 * overwhelming majority of a backlog is caused by a single type of poll message (e.g. contact
 * delete confirmations) and it is desired that the registrar be able to ACK the rest of the poll
 * messages in-band.
 *
 * <p>This command ACKs both {@link OneTime} and {@link Autorenew} poll messages. The main
 * difference is that one-time poll messages are deleted when ACKed whereas Autorenews are sometimes
 * modified and re-saved instead, if the corresponding domain is still active.
 *
 * <p>In all cases it is not permissible to ACK a poll message until it has been delivered (i.e. its
 * event time is in the past), same as through EPP.
 */
@Parameters(separators = " =", commandDescription = "Acknowledge one-time poll messages.")
final class AckPollMessagesCommand implements CommandWithRemoteApi {

  @Parameter(
      names = {"-c", "--client"},
      description = "Client identifier of the registrar whose poll messages should be ACKed",
      required = true
  )
  private String clientId;

  @Parameter(
      names = {"-m", "--message"},
      description = "A string that poll messages to be ACKed must contain (else all will be ACKed)"
  )
  private String message;

  @Parameter(
      names = {"-d", "--dry_run"},
      description = "Do not actually commit any mutations")
  private boolean dryRun;

  @Inject Clock clock;

  private static final int BATCH_SIZE = 20;

  @Override
  public void run() {
    DateTime now = clock.nowUtc();
    QueryKeys<PollMessage> query = getPollMessagesQuery(clientId, now).keys();
    for (List<Key<PollMessage>> keys : Iterables.partition(query, BATCH_SIZE)) {
      tm().transact(
              () -> {
                // Load poll messages and filter to just those of interest.
                ImmutableList<PollMessage> pollMessages =
                    ofy().load().keys(keys).values().stream()
                        .filter(pm -> isNullOrEmpty(message) || pm.getMsg().contains(message))
                        .collect(toImmutableList());
                if (!dryRun) {
                  pollMessages.forEach(PollFlowUtils::ackPollMessage);
                }
                pollMessages.forEach(
                    pm ->
                        System.out.println(
                            Joiner.on(',')
                                .join(
                                    makePollMessageExternalId(pm),
                                    pm.getEventTime(),
                                    pm.getMsg())));
              });
    }
  }
}
