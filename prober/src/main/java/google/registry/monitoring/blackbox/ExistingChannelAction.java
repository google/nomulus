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

package google.registry.monitoring.blackbox;

import com.google.auto.value.AutoValue;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

/**
 * Subclass of {@link ProbingAction} that takes in an existing channel
 */
@AutoValue
public abstract class ExistingChannelAction extends ProbingAction {

  public static ExistingChannelAction.Builder builder() {
    return new AutoValue_ExistingChannelAction.Builder().path("");
  }

  @Override
  public abstract Builder toBuilder();

  @Override
  public ChannelFuture call() {
    //only thing necessary is reset the PROBING_ACTION_KEY of
    //the channel to reflect the current ProbingAction
    channel().attr(PROBING_ACTION_KEY).set(this);
    return super.call();
  }

  @AutoValue.Builder
  public static abstract class Builder extends ProbingAction.Builder<Builder, ExistingChannelAction> {
    //specifies channel in this builder
    public abstract Builder channel(Channel channel);
  }
}
