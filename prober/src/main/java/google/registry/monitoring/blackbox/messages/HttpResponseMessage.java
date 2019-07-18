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

package google.registry.monitoring.blackbox.messages;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * {@link InboundMessageType} subtype that acts identically to {@link DefaultFullHttpResponse}
 */
public class HttpResponseMessage extends DefaultFullHttpResponse implements InboundMessageType {

  public HttpResponseMessage(HttpVersion version, HttpResponseStatus status) {
    super(version, status);
  }

  public HttpResponseMessage(HttpVersion version, HttpResponseStatus status, ByteBuf content) {
    super(version, status, content);
  }

  /** Used for pipeline conversion from {@link FullHttpResponse} to {@link HttpResponseMessage} */
  public static HttpResponseMessage fromResponse(FullHttpResponse response) {
    HttpResponseMessage finalResponse;
    ByteBuf buf = response.content();

    if (buf == null)
      finalResponse = new HttpResponseMessage(HttpVersion.HTTP_1_1, response.status());
    else
      finalResponse = new HttpResponseMessage(HttpVersion.HTTP_1_1, response.status(), buf);

    response.headers().forEach((entry) -> finalResponse.headers().set(entry.getKey(), entry.getValue()));

    return finalResponse;
  }
}
