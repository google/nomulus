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

import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.poll.PollFlowUtils.getFirstPollMessage;
import static google.registry.flows.poll.PollFlowUtils.getPollMessageCount;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACK_MESSAGE;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_NO_MESSAGES;
import static google.registry.model.poll.PollMessageExternalKeyConverter.makePollMessageExternalId;

import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.PollMessageId;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.MessageQueueInfo;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessageExternalKeyConverter;
import google.registry.util.Clock;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow for requesting {@link PollMessage}s.
 *
 * <p>This flow uses an eventually consistent Datastore query to return the oldest poll message for
 * the registrar, as well as the total number of pending messages. Note that poll messages whose
 * event time is in the future (i.e. they are speculative and could still be changed or rescinded)
 * are ignored. The externally visible id for the poll message that the registrar sees is generated
 * by {@link PollMessageExternalKeyConverter}.
 *
 * @error {@link PollRequestFlow.UnexpectedMessageIdException}
 */
public class PollRequestFlow implements Flow {

  @Inject ExtensionManager extensionManager;
  @Inject @ClientId String clientId;
  @Inject @PollMessageId String messageId;
  @Inject Clock clock;
  @Inject EppResponse.Builder responseBuilder;
  @Inject PollRequestFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.validate();  // There are no legal extensions for this flow.
    validateClientIsLoggedIn(clientId);
    if (!messageId.isEmpty()) {
      throw new UnexpectedMessageIdException();
    }
    // Return the oldest message from the queue.
    DateTime now = clock.nowUtc();
    Optional<PollMessage> maybePollMessage = getFirstPollMessage(clientId, now);
    if (!maybePollMessage.isPresent()) {
      return responseBuilder.setResultFromCode(SUCCESS_WITH_NO_MESSAGES).build();
    }
    PollMessage pollMessage = maybePollMessage.get();
    return responseBuilder
        .setResultFromCode(SUCCESS_WITH_ACK_MESSAGE)
        .setMessageQueueInfo(
            new MessageQueueInfo.Builder()
                .setQueueDate(pollMessage.getEventTime())
                .setMsg(pollMessage.getMsg())
                .setQueueLength(getPollMessageCount(clientId, now))
                .setMessageId(makePollMessageExternalId(pollMessage))
                .build())
        .setMultipleResData(pollMessage.getResponseData())
        .build();
  }

  /** Unexpected message id. */
  static class UnexpectedMessageIdException extends ParameterValueSyntaxErrorException {
    public UnexpectedMessageIdException() {
      super("Unexpected message id");
    }
  }
}
