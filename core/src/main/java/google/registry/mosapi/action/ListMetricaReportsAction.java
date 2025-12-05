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

/** An action that lists available MoSAPI Domain METRICA reports for a given TLD. */
@Action(
    service = Action.Service.BACKEND,
    path = ListMetricaReportsAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_ADMIN)
public class ListMetricaReportsAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/_dr/mosapi/listMetricaReports";
  public static final String TLD_PARAM = "tld";
  public static final String START_DATE_PARAM = "startDate";
  public static final String END_DATE_PARAM = "endDate";

  private final MosApiMetricaService metricaService;
  private final Response response;
  private final Gson gson;
  private final String tld;
  private final Optional<LocalDate> startDate;
  private final Optional<LocalDate> endDate;

  @Inject
  public ListMetricaReportsAction(
      MosApiMetricaService metricaService,
      Response response,
      Gson gson,
      @Parameter(TLD_PARAM) String tld,
      @Parameter(START_DATE_PARAM) Optional<LocalDate> startDate,
      @Parameter(END_DATE_PARAM) Optional<LocalDate> endDate) {
    this.metricaService = metricaService;
    this.response = response;
    this.gson = gson;
    this.tld = tld;
    this.startDate = startDate;
    this.endDate = endDate;
  }

  @Override
  public void run() {
    try {
      response.setContentType(MediaType.JSON_UTF_8);
      response.setPayload(
          gson.toJson(metricaService.listAvailableReports(tld, startDate, endDate)));
    } catch (MosApiException e) {
      logger.atWarning().withCause(e).log(
          "MoSAPI client failed to list metrica report for TLD: %s", tld);
      throw new ServiceUnavailableException("Error listing METRICA reports.");
    }
  }
}
