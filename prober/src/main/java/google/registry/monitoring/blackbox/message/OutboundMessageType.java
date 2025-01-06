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

package google.registry.monitoring.blackbox.message;

import google.registry.monitoring.blackbox.exception.UndeterminedStateException;

/**
 * Marker Interface that is implemented by all classes that serve as {@code outboundMessages} in
 * channel pipeline
 */
public interface OutboundMessageType {

  /**
   * All {@link OutboundMessageType} implementing classes should be able to be modified by token
   * with String arguments
   */
  OutboundMessageType modifyMessage(String... args) throws UndeterminedStateException;

  /**
   * Necessary to inform metrics collector what kind of message is sent down {@link
   * io.netty.channel.ChannelPipeline}. Not equivalent to toString, as to different instances will
   * have the same name if they perform the same action.
   */
  String name();

  /**
   * Necessary to inform metrics collector what kind of message is sent inbound {@link
   * io.netty.channel.ChannelPipeline}. Equivalent to {@code name} but for {@link
   * InboundMessageType}.
   */
  String responseName();
}
