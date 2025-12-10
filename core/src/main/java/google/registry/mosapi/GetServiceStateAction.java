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

import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.request.Action;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.Optional;

/** An action that returns the current MoSAPI service state for a given TLD or all TLDs. */
@Action(
    service = Action.Service.BACKEND,
    path = GetServiceStateAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_ADMIN)
public class GetServiceStateAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/_dr/mosapi/getServiceState";
  public static final String TLD_PARAM = "tld";

  private final MosApiStateService stateService;
  private final Response response;
  private final Gson gson;
  private final Optional<String> tld;

  @Inject
  public GetServiceStateAction(
      MosApiStateService stateService,
      Response response,
      Gson gson,
      @Parameter(TLD_PARAM) Optional<String> tld) {
    this.stateService = stateService;
    this.response = response;
    this.gson = gson;
    this.tld = tld;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.JSON_UTF_8);
    try {
      if (tld.isPresent()) {
        response.setPayload(gson.toJson(stateService.getServiceStateSummary(tld.get())));
      } else {
        response.setPayload(gson.toJson(stateService.getAllServiceStateSummaries()));
      }
    } catch (MosApiException e) {
      logger.atWarning().withCause(e).log(
          "MoSAPI client failed to get Service state for %s TLD", tld.orElse("all"));
      throw new ServiceUnavailableException("Error fetching MoSAPI service state.");
    }
  }
}
