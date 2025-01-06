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

package google.registry.request;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;
import static com.google.common.net.HttpHeaders.X_CONTENT_TYPE_OPTIONS;
import static com.google.common.net.MediaType.JSON_UTF_8;
import static org.json.simple.JSONValue.toJSONString;

import java.util.Map;
import javax.inject.Inject;
import org.joda.time.DateTime;

/** JSON response object. */
public class JsonResponse {

  /** String prefixed to all JSON-like responses to break {@code eval()}. */
  public static final String JSON_SAFETY_PREFIX = ")]}'\n";

  protected final Response response;

  @Inject
  public JsonResponse(Response rsp) {
    this.response = rsp;
  }

  /**
   * Sets the status.
   *
   * @see Response#setStatus
   */
  public void setStatus(int status) {
    response.setStatus(status);
  }

  /** Writes the JSON map to the HTTP payload; call this exactly once.   */
  public void setPayload(Map<String, ?> responseMap) {
    response.setContentType(JSON_UTF_8);
    // This prevents IE from MIME-sniffing a response away from the declared Content-Type.
    response.setHeader(X_CONTENT_TYPE_OPTIONS, "nosniff");
    // This is a defense in depth that prevents browsers from trying to render the content of the
    // response, even if all else fails. It's basically another anti-sniffing mechanism in the sense
    // that if you hit this url directly, it would try to download the file instead of showing it.
    response.setHeader(CONTENT_DISPOSITION, "attachment");
    response.setPayload(JSON_SAFETY_PREFIX + toJSONString(checkNotNull(responseMap)));
  }

  /**
   * Sets the header.
   *
   * @see Response#setHeader
   */
  public void setHeader(String header, String value) {
    response.setHeader(header, value);
  }

  /**
   * Sets the date header.
   *
   * <p>see Response#setDateHeader
   */
  public void setDateHeader(String header, DateTime timestamp) {
    response.setDateHeader(header, timestamp);
  }
}
