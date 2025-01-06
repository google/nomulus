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

package google.registry.model.poll;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import org.joda.time.DateTime;

/** Information about the message queue for the currently logged in registrar. */
public class MessageQueueInfo extends ImmutableObject {

  /** The date and time that the current message was enqueued. */
  @XmlElement(name = "qDate")
  DateTime queueDate;

  /** A human-readable message. */
  String msg;

  /** The number of messages currently in the queue. */
  @XmlAttribute(name = "count")
  Integer queueLength;

  /** The id of the message currently at the head of the queue. */
  @XmlAttribute(name = "id")
  String messageId;

  /** A builder for constructing a {@link MessageQueueInfo}, since it's immutable. */
  public static class Builder extends Buildable.Builder<MessageQueueInfo> {
    public Builder setQueueDate(DateTime queueDate) {
      getInstance().queueDate = queueDate;
      return this;
    }

    public Builder setMsg(String msg) {
      getInstance().msg = msg;
      return this;
    }

    public Builder setQueueLength(int queueLength) {
      checkArgument(queueLength >= 0);
      getInstance().queueLength = queueLength;
      return this;
    }

    public Builder setMessageId(String messageId) {
      getInstance().messageId = messageId;
      return this;
    }

    @Override
    public MessageQueueInfo build() {
      checkNotNull(getInstance().messageId);
      checkNotNull(getInstance().queueLength);
      return super.build();
    }
  }
}
