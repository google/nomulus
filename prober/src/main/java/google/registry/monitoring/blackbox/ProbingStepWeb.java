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

import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import io.netty.channel.AbstractChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import javax.inject.Inject;

/**
 * {@link ProbingStep} subclass that deals with setps in the WebWhoisFlow
 *
 * @param <C> refer to {@code C} in {@link ProbingStep}
 *
 * <p>Only passes in requisite {@link Protocol} and {@link OutboundMessageType} to parent constructor</p>
 */
public class ProbingStepWeb<C extends AbstractChannel> extends ProbingStep<C>{
  @Inject
  public ProbingStepWeb(Protocol protocol) {
    super(protocol, new HttpRequestMessage(HttpVersion.HTTP_1_1, HttpMethod.GET, ""));
    duration = DEFAULT_DURATION;
  }

  @Override
  Protocol protocol() {
    return protocol;
  }
}
