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

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import google.registry.mosapi.model.MosApiErrorResponse;
import google.registry.mosapi.model.TldServiceState;
import jakarta.inject.Inject;
import java.util.Collections;
import okhttp3.Response;

/** Facade for MoSAPI's service monitoring endpoints. */
public class ServiceMonitoringClient {
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
    String endpoint = "v2/monitoring/state";
    try (Response response =
        mosApiClient.sendGetRequest(
            tld, endpoint, Collections.emptyMap(), Collections.emptyMap())) {
      if (!response.isSuccessful()) {
        throw MosApiException.create(
            gson.fromJson(response.body().charStream(), MosApiErrorResponse.class));
      }
      return gson.fromJson(response.body().charStream(), TldServiceState.class);
    } catch (JsonIOException | JsonSyntaxException e) {
      // Catch Gson's runtime exceptions (parsing errors) and wrap them
      throw new MosApiException("Failed to parse TLD service state response", e);
    }
  }
}
