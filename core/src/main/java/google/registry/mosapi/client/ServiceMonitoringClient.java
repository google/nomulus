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

package google.registry.mosapi.client;

import com.google.gson.Gson;
import google.registry.mosapi.dto.servicemonitoring.ServiceAlarm;
import google.registry.mosapi.dto.servicemonitoring.ServiceDowntime;
import google.registry.mosapi.dto.servicemonitoring.TldServiceState;
import google.registry.mosapi.exception.MosApiException;
import jakarta.inject.Inject;
import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.Collections;

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
  public TldServiceState getServiceState(String tld) throws MosApiException {
    String endpoint = String.format("v2/monitoring/state");
    HttpResponse<String> response =
        mosApiClient.sendGetRequest(tld, endpoint, Collections.emptyMap(), Collections.emptyMap());
    return gson.fromJson(response.body(), TldServiceState.class);
  }

  /**
   * Fetches the total downtime for a specific service over a rolling week period.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.3</a>
   */
  public ServiceDowntime getDowntime(String tld, String service) throws MosApiException {
    String endpoint = String.format("v2/monitoring/%s/downtime", service);
    HttpResponse<String> response =
        mosApiClient.sendGetRequest(tld, endpoint, Collections.emptyMap(), Collections.emptyMap());
    switch (response.statusCode()) {
      case HttpURLConnection.HTTP_OK:
        return gson.fromJson(response.body(), ServiceDowntime.class);
      case HttpURLConnection.HTTP_NOT_FOUND:
        return new ServiceDowntime(2, 0, 0, true);
      default:
        throw new MosApiException(
            String.format(
                "Request to %s failed with status code %d", response.uri(), response.statusCode()));
    }
  }

  /**
   * Checks if a specific service has an active alarm.
   *
   * @see <a href="https://www.icann.org/mosapi-specification.pdf">ICANN MoSAPI Specification,
   *     Section 5.2</a>
   */
  public ServiceAlarm serviceAlarmed(String tld, String service) throws MosApiException {
    String endpoint = String.format("v2/monitoring/%s/alarmed", service);
    HttpResponse<String> response =
        mosApiClient.sendGetRequest(tld, endpoint, Collections.emptyMap(), Collections.emptyMap());

    switch (response.statusCode()) {
      case HttpURLConnection.HTTP_OK:
        return gson.fromJson(response.body(), ServiceAlarm.class);
      case HttpURLConnection.HTTP_NOT_FOUND:
        return new ServiceAlarm(2, 0, "Disabled");
      default:
        throw new MosApiException(
            String.format(
                "Request to %s failed with status code %d", response.uri(), response.statusCode()));
    }
  }
}
