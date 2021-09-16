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

package google.registry.flows;

import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static java.nio.charset.StandardCharsets.UTF_8;

import dagger.Module;
import dagger.Provides;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/** Runs EPP commands directly without logging in, verifying an XSRF token from the tool. */
@Action(
    service = Action.Service.TOOLS,
    path = EppToolAction.PATH,
    method = Method.POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class EppToolAction implements Runnable {

  public static final String PATH = "/_dr/epptool";

  @Inject
  @Parameter("clientId")
  String registrarId;

  @Inject @Parameter("superuser") boolean isSuperuser;
  @Inject @Parameter("dryRun") boolean isDryRun;
  @Inject @Parameter("xml") String xml;
  @Inject EppRequestHandler eppRequestHandler;
  @Inject EppToolAction() {}

  @Override
  public void run() {
    eppRequestHandler.executeEpp(
        new StatelessRequestSessionMetadata(
            registrarId, ProtocolDefinition.getVisibleServiceExtensionUris()),
        new PasswordOnlyTransportCredentials(),
        EppRequestSource.TOOL,
        isDryRun,
        isSuperuser,
        xml.getBytes(UTF_8));
  }

  /** Dagger module for the epp tool endpoint. */
  @Module
  public static final class EppToolModule {

    // TODO(b/29139545): Make parameters consistent across the graph. @Parameter("dryRun") is
    // already provided elsewhere in the graph and happens to work for us but that's just luck.

    @Provides
    @Parameter("xml")
    static String provideXml(HttpServletRequest req) {
      return extractRequiredParameter(req, "xml");
    }

    @Provides
    @Parameter("superuser")
    static boolean provideIsSuperuser(HttpServletRequest req) {
      return extractBooleanParameter(req, "superuser");
    }

    @Provides
    @Parameter("clientId")
    static String provideClientId(HttpServletRequest req) {
      return extractRequiredParameter(req, "clientId");
    }
  }
}
