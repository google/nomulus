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

package google.registry.mosapi;

import com.google.common.base.Throwables;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import google.registry.mosapi.MosApiModels.TldServiceState;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Collections;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Facade for MoSAPI's service monitoring endpoints. */
public class ServiceMonitoringClient {

  private static final String MONITORING_STATE_ENDPOINT = "v2/monitoring/state";
  private final MosApiClient mosApiClient;
  private final Gson gson;

  @Inject
  public ServiceMonitoringClient(MosApiClient mosApiClient, Gson gson) {
    this.mosApiClient = mosApiClient;
    this.gson = gson;
  }

  /**
   * Fetches the current state of all monitored services for a given TLD.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.1</a>
   */
  public TldServiceState getTldServiceState(String tld) throws MosApiException {
    try (Response response =
        mosApiClient.sendGetRequest(
            tld, MONITORING_STATE_ENDPOINT, Collections.emptyMap(), Collections.emptyMap())) {

      ResponseBody responseBody = response.body();
      if (responseBody == null) {
        throw new MosApiException(
            String.format(
                "MoSAPI Service Monitoring API " + "returned an empty body with status: %d",
                response.code()));
      }
      String bodyString = responseBody.string();
      if (!response.isSuccessful()) {
        throw parseErrorResponse(response.code(), bodyString);
      }
      return gson.fromJson(bodyString, TldServiceState.class);
    } catch (IOException | JsonParseException e) {
      Throwables.throwIfInstanceOf(e, MosApiException.class);
      // Catch Gson's runtime exceptions (parsing errors) and wrap them
      throw new MosApiException("Failed to parse TLD service state response", e);
    }
  }

  /** Parses an unsuccessful MoSAPI response into a domain-specific {@link MosApiException}. */
  private MosApiException parseErrorResponse(int statusCode, String bodyString) {
    try {
      MosApiErrorResponse error = gson.fromJson(bodyString, MosApiErrorResponse.class);
      return MosApiException.create(error);
    } catch (JsonParseException e) {
      return new MosApiException(
          String.format("MoSAPI json parsing error (%d): %s", statusCode, bodyString), e);
    }
  }
}
