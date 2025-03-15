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

package google.registry.request;

import static com.google.common.base.Verify.verifyNotNull;

import jakarta.inject.Inject;
import java.util.Map;

/** Runner for actions that read and write JSON objects. */
public final class JsonActionRunner {

  /** Interface for actions that read and write JSON objects. */
  public interface JsonAction {

    /**
     * Handles JSON HTTP request.
     *
     * @param json object extracted from request body
     * @return an arbitrary JSON object, which is never {@code null}
     * @throws HttpException to send a non-200 status code / message to client
     */
    Map<String, ?> handleJsonRequest(Map<String, ?> json);
  }

  Map<String, Object> payload;
  JsonResponse response;

  @Inject public JsonActionRunner(@JsonPayload Map<String, Object> payload, JsonResponse response) {
    this.payload = payload;
    this.response = response;
  }

  /** Delegates request to {@code action}. */
  public void run(JsonAction action) {
    response.setPayload(
        verifyNotNull(
            action.handleJsonRequest(payload),
            "handleJsonRequest() returned null"));
  }
}
