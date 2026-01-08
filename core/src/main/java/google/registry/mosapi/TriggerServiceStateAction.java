// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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
import google.registry.request.Action;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;

/**
 * An action that triggers Metrics action for the current MoSAPI service state result for all TLDs.
 */
@Action(
    service = Action.Service.BACKEND,
    path = TriggerServiceStateAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_ADMIN)
public class TriggerServiceStateAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PATH = "/_dr/task/triggerMosApiServiceState";
  private final MosApiStateService stateService;
  private final Response response;

  @Inject
  public TriggerServiceStateAction(MosApiStateService stateService, Response response) {
    this.stateService = stateService;
    this.response = response;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    try {
      logger.atInfo().log("Beginning to trigger MoSAPI metrics for all TLDs.");
      stateService.triggerMetricsForAllServiceStateSummaries();
      response.setStatus(200);
      response.setPayload("MoSAPI metrics triggered successfully for all TLDs.");
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Error triggering MoSAPI metrics.");
      throw new InternalServerErrorException("Failed to process MoSAPI metrics.");
    }
  }
}
