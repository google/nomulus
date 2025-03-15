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

package google.registry.monitoring.blackbox.message;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.inject.Inject;
import java.util.Arrays;

/**
 * {@link OutboundMessageType} subtype that acts identically to {@link DefaultFullHttpRequest}.
 *
 * <p>As it is an {@link OutboundMessageType} subtype, there is a {@code modifyMessage} method that
 * modifies the request to reflect the new host and optional path. We also implement a {@code name}
 * method, which returns a standard name and the current hostname.
 */
public class HttpRequestMessage extends DefaultFullHttpRequest implements OutboundMessageType {

  @Inject
  public HttpRequestMessage() {
    this(HttpVersion.HTTP_1_1, HttpMethod.GET, "");
  }

  private HttpRequestMessage(HttpVersion httpVersion, HttpMethod method, String uri) {
    super(httpVersion, method, uri);
  }

  private HttpRequestMessage(
      HttpVersion httpVersion, HttpMethod method, String uri, ByteBuf content) {
    super(httpVersion, method, uri, content);
  }

  /** Used for conversion from {@link FullHttpRequest} to {@link HttpRequestMessage} */
  public HttpRequestMessage(FullHttpRequest request) {
    this(request.protocolVersion(), request.method(), request.uri(), request.content());
    request.headers().forEach((entry) -> headers().set(entry.getKey(), entry.getValue()));
  }

  @Override
  public HttpRequestMessage setUri(String path) {
    super.setUri(path);
    return this;
  }

  /** Modifies headers to reflect new host and new path if applicable. */
  @Override
  public HttpRequestMessage modifyMessage(String... args) throws IllegalArgumentException {
    if (args.length == 1 || args.length == 2) {
      headers().set("host", args[0]);
      if (args.length == 2) {
        setUri(args[1]);
      }

      return this;

    } else {
      throw new IllegalArgumentException(
          String.format(
              "Wrong number of arguments present for modifying HttpRequestMessage."
                  + " Received %d arguments instead of 2. Received arguments: "
                  + Arrays.toString(args),
              args.length));
    }
  }

  @Override
  public String name() {
    return String.format("Http(s) Request on: %s", headers().get("host"));
  }

  @Override
  public String responseName() {
    return "Http Response";
  }
}
