// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.poll.PollFlowUtils.ackPollMessage;
import static google.registry.flows.poll.PollFlowUtils.getPollMessageCount;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_NO_MESSAGES;
import static google.registry.model.poll.PollMessageExternalKeyConverter.makePollMessageExternalId;
import static google.registry.model.poll.PollMessageExternalKeyConverter.parsePollMessageExternalId;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import google.registry.flows.EppException;
import google.registry.flows.EppException.AuthorizationErrorException;
import google.registry.flows.EppException.ObjectDoesNotExistException;
import google.registry.flows.EppException.ParameterValueSyntaxErrorException;
import google.registry.flows.EppException.RequiredParameterMissingException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.PollMessageId;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.MutatingFlow;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.MessageQueueInfo;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessageExternalKeyConverter;
import google.registry.model.poll.PollMessageExternalKeyConverter.PollMessageExternalKeyParseException;
import google.registry.persistence.IsolationLevel;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow for acknowledging {@link PollMessage}s.
 *
 * <p>Registrars refer to poll messages using an externally visible id generated by {@link
 * PollMessageExternalKeyConverter}. One-time poll messages are deleted from the database once they
 * are ACKed, whereas autorenew poll messages are simply marked as read, and won't be delivered
 * again until the next year of their recurrence.
 *
 * @error {@link PollAckFlow.InvalidMessageIdException}
 * @error {@link PollAckFlow.MessageDoesNotExistException}
 * @error {@link PollAckFlow.MissingMessageIdException}
 * @error {@link PollAckFlow.NotAuthorizedToAckMessageException}
 */
@IsolationLevel(value = TransactionIsolationLevel.TRANSACTION_READ_COMMITTED)
public final class PollAckFlow implements MutatingFlow {

  @Inject ExtensionManager extensionManager;
  @Inject @RegistrarId String registrarId;
  @Inject @PollMessageId String messageId;
  @Inject EppResponse.Builder responseBuilder;

  @Inject PollAckFlow() {}

  @Override
  public EppResponse run() throws EppException {
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate(); // There are no legal extensions for this flow.
    if (messageId.isEmpty()) {
      throw new MissingMessageIdException();
    }

    VKey<PollMessage> pollMessageKey;
    // Try parsing the messageId, and throw an exception if it's invalid.
    try {
      pollMessageKey = parsePollMessageExternalId(messageId);
    } catch (PollMessageExternalKeyParseException e) {
      throw new InvalidMessageIdException(messageId);
    }

    final DateTime now = tm().getTransactionTime();

    // Load the message to be acked. If a message is queued to be delivered in the future, we treat
    // it as if it doesn't exist yet. Same for if the message ID year isn't the same as the actual
    // poll message's event time (that means they're passing in an old already-acked ID).
    Optional<PollMessage> maybePollMessage = tm().loadByKeyIfPresent(pollMessageKey);
    if (maybePollMessage.isEmpty()
        || !isBeforeOrAt(maybePollMessage.get().getEventTime(), now)
        || !makePollMessageExternalId(maybePollMessage.get()).equals(messageId)) {
      throw new MessageDoesNotExistException(messageId);
    }
    PollMessage pollMessage = maybePollMessage.get();

    // Make sure this client is authorized to ack this message. It could be that the message is
    // supposed to go to a different registrar.
    if (!registrarId.equals(pollMessage.getRegistrarId())) {
      throw new NotAuthorizedToAckMessageException();
    }

    // This keeps track of whether we should include the current acked message in the updated
    // message count that's returned to the user. The only case where we do so is if an autorenew
    // poll message is acked, but its next event is already ready to be delivered.
    ackPollMessage(pollMessage);

    // We need to return the new queue length. If this was the last message in the queue being
    // acked, then we return a special status code indicating that. Note that the query will
    // include the message being acked.

    int messageCount = getPollMessageCount(registrarId, now);
    if (messageCount <= 0) {
      return responseBuilder.setResultFromCode(SUCCESS_WITH_NO_MESSAGES).build();
    }
    return responseBuilder
        .setMessageQueueInfo(new MessageQueueInfo.Builder()
            .setQueueLength(messageCount)
            .setMessageId(messageId)
            .build())
        .build();
  }

  /** Registrar is not authorized to ack this message. */
  static class NotAuthorizedToAckMessageException extends AuthorizationErrorException {
    public NotAuthorizedToAckMessageException() {
      super("Registrar is not authorized to ack this message");
    }
  }

  /** Message with this id does not exist. */
  public static class MessageDoesNotExistException extends ObjectDoesNotExistException {
    public MessageDoesNotExistException(String messageIdString) {
      super(PollMessage.class, messageIdString);
    }
  }

  /** Message id is invalid. */
  static class InvalidMessageIdException extends ParameterValueSyntaxErrorException {
    public InvalidMessageIdException(String messageIdStr) {
      super(String.format("Message id \"%s\" is invalid", messageIdStr));
    }
  }

  /** Message id is required. */
  static class MissingMessageIdException extends RequiredParameterMissingException {
    public MissingMessageIdException() {
      super("Message id is required");
    }
  }
}
