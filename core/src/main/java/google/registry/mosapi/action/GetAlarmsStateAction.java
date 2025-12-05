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

import static google.registry.request.Action.Method.GET;

import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.mosapi.services.MosApiAlarmService;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;

/** An action that checks the alarm status for all configured MoSAPI entities. */
@Action(
    service = Action.Service.BACKEND,
    path = GetAlarmsStateAction.PATH,
    method = GET,
    auth = Auth.AUTH_ADMIN)
public class GetAlarmsStateAction implements Runnable {
  public static final String PATH = "/_dr/mosapi/checkalarm";

  private final MosApiAlarmService alarmsService;
  private final Response response;
  private final Gson gson;

  @Inject
  public GetAlarmsStateAction(MosApiAlarmService alarmsService, Response response, Gson gson) {
    this.alarmsService = alarmsService;
    this.response = response;
    this.gson = gson;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.JSON_UTF_8);
    response.setPayload(gson.toJson(alarmsService.checkAllAlarms()));
  }
}
