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

import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.mosapi.services.MosApiDowntimeService;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import jakarta.inject.Inject;
import java.util.Optional;

/** An action that returns the MoSAPI downtime for a given TLD and service. */
@Action(
    service = Action.Service.BACKEND,
    path = GetServiceDowntimeAction.PATH,
    method = Action.Method.GET,
    auth = Auth.AUTH_ADMIN)
public class GetServiceDowntimeAction implements Runnable {

  public static final String PATH = "/_dr/mosapi/getServiceDowntime";
  public static final String TLD_PARAM = "tld";

  private final MosApiDowntimeService downtimeService;
  private final Response response;
  private final Gson gson;
  private final Optional<String> tld;

  @Inject
  public GetServiceDowntimeAction(
      MosApiDowntimeService downtimeService,
      Response response,
      Gson gson,
      @Parameter(TLD_PARAM) Optional<String> tld) {
    this.downtimeService = downtimeService;
    this.response = response;
    this.gson = gson;
    this.tld = tld;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.JSON_UTF_8);
    if (tld.isPresent()) {
      // Handle the case for a single TLD.
      response.setPayload(gson.toJson(downtimeService.getDowntimeForTld(tld.get())));
    } else {
      // Handle the case for all TLDs.
      response.setPayload(gson.toJson(downtimeService.getDowntimeForAllTlds()));
    }
  }
}
