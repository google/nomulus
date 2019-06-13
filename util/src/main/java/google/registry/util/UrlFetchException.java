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

package google.registry.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.api.urlfetch.HTTPHeader;
import com.google.appengine.api.urlfetch.HTTPRequest;
import com.google.appengine.api.urlfetch.HTTPResponse;

/**
 * Exception for when App Engine HTTP requests return a bad response.
 *
 * <p>This class displays lots of helpful troubleshooting information.
 */
public class UrlFetchException extends RuntimeException {

  private final HTTPRequest req;
  private final HTTPResponse rsp;

  public UrlFetchException(String message, HTTPRequest req, HTTPResponse rsp) {
    super(message);
    this.req = checkNotNull(req, "req");
    this.rsp = checkNotNull(rsp, "rsp");
  }

  @Override
  public String getMessage() {
    StringBuilder res =
        new StringBuilder(2048 + rsp.getContent().length)
            .append(
                String.format(
                    "%s: %s (HTTP Status %d)\nX-Fetch-URL: %s\nX-Final-URL: %s\n",
                    getClass().getSimpleName(),
                    super.getMessage(),
                    rsp.getResponseCode(),
                    req.getURL().toString(),
                    rsp.getFinalUrl()));
    for (HTTPHeader header : rsp.getHeadersUncombined()) {
      res.append(header.getName());
      res.append(": ");
      res.append(header.getValue());
      res.append('\n');
    }
    res.append(">>>\n");
    res.append(new String(rsp.getContent(), UTF_8));
    res.append("\n<<<");
    return res.toString();
  }
}
