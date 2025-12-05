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

package google.registry.mosapi.action;

import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.mosapi.exception.MosApiException;
import google.registry.mosapi.services.MosApiMetricaService;
import google.registry.request.Action;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.Optional;

@Action(
    service = Action.Service.BACKEND,
    path = GetMetricaReportAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_ADMIN)
public class GetMetricaReportAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/_dr/mosapi/getMetricaReport";
  public static final String TLD_PARAM = "tld";
  public static final String DATE_PARAM = "date";

  private final MosApiMetricaService metricaService;
  private final Response response;
  private final Gson gson;
  private final String tld;
  private final Optional<LocalDate> date;

  @Inject
  public GetMetricaReportAction(
      MosApiMetricaService metricaService,
      Response response,
      Gson gson,
      @Parameter(TLD_PARAM) String tld,
      @Parameter(DATE_PARAM) Optional<LocalDate> date) {
    this.metricaService = metricaService;
    this.response = response;
    this.gson = gson;
    this.tld = tld;
    this.date = date;
  }

  @Override
  public void run() {
    try {
      response.setContentType(MediaType.JSON_UTF_8);
      response.setPayload(gson.toJson(metricaService.getReport(tld, date)));
    } catch (MosApiException e) {
      logger.atWarning().withCause(e).log(
          "MoSAPI client failed to get response for Metrica report for TLD: %s", tld);
      throw new ServiceUnavailableException("Error fetching METRICA report.");
    }
  }
}
