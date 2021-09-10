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

package google.registry.flows.session;

import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_AND_CLOSE;

import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.SessionMetadata;
import google.registry.model.eppoutput.EppResponse;
import javax.inject.Inject;

/**
 * An EPP flow for logout.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 */
public class LogoutFlow implements Flow {

  @Inject ExtensionManager extensionManager;
  @Inject @RegistrarId String registrarId;
  @Inject SessionMetadata sessionMetadata;
  @Inject EppResponse.Builder responseBuilder;
  @Inject LogoutFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.validate();  // There are no legal extensions for this flow.
    validateRegistrarIsLoggedIn(registrarId);
    sessionMetadata.invalidate();
    return responseBuilder.setResultFromCode(SUCCESS_AND_CLOSE).build();
  }
}
