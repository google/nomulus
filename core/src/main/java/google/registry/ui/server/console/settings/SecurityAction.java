// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console.settings;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;

import com.google.api.client.http.HttpStatusCodes;
import com.google.gson.Gson;
import google.registry.flows.certs.CertificateChecker;
import google.registry.flows.certs.CertificateChecker.InsecureCertificateException;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.ui.server.registrar.JsonGetAction;
import java.util.Optional;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

@Action(
    service = Action.Service.DEFAULT,
    path = SecurityAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class SecurityAction implements JsonGetAction {
  static final String PATH = "/console-api/settings/security";
  private final HttpServletRequest req;
  private final AuthResult authResult;
  private final Response response;
  private final Gson gson;
  private final String registrarId;
  private AuthenticatedRegistrarAccessor registrarAccessor;
  private Optional<Registrar> registrar;
  private CertificateChecker certificateChecker;

  @Inject
  public SecurityAction(
      HttpServletRequest req,
      AuthResult authResult,
      Response response,
      Gson gson,
      CertificateChecker certificateChecker,
      AuthenticatedRegistrarAccessor registrarAccessor,
      @Parameter("registrarId") String registrarId,
      @Parameter("registrar") Optional<Registrar> registrar) {
    this.req = req;
    this.authResult = authResult;
    this.response = response;
    this.gson = gson;
    this.registrarId = registrarId;
    this.registrarAccessor = registrarAccessor;
    this.registrar = registrar;
    this.certificateChecker = certificateChecker;
  }

  @Override
  public void run() {
    if (req.getMethod().equals(GET.toString())) {
      getHandler();
    } else {
      postHandler();
    }
  }

  private void getHandler() {
    try {
      Registrar r = registrarAccessor.getRegistrar(registrarId);
      response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
      response.setPayload(gson.toJson(r));
    } catch (RegistrarAccessDeniedException e) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      response.setPayload(e.getMessage());
    }
  }

  private void postHandler() {
    User user = authResult.userAuthInfo().get().consoleUser().get();
    if (!user.getUserRoles().hasPermission(registrarId, ConsolePermission.EDIT_REGISTRAR_DETAILS)) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }
    if (!registrar.isPresent()) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      response.setPayload(gson.toJson("'registrar' parameter is not present"));
      return;
    }

    try {
      Registrar savedRegistrar = registrarAccessor.getRegistrar(registrarId);

      tm().transact(
              () -> {
                tm().getEntityManager().detach(savedRegistrar);

                Registrar.Builder updatedRegistrar =
                    savedRegistrar
                        .asBuilder()
                        .setIpAddressAllowList(registrar.get().getIpAddressAllowList());
                if (registrar.get().getClientCertificate().isPresent()
                    && validateCertificate(
                        savedRegistrar.getClientCertificate(),
                        registrar.get().getClientCertificate().get())) {
                  updatedRegistrar.setClientCertificate(
                      registrar.get().getClientCertificate().get(), tm().getTransactionTime());
                }
                if (registrar.get().getFailoverClientCertificate().isPresent()
                    && validateCertificate(
                        savedRegistrar.getFailoverClientCertificate(),
                        registrar.get().getFailoverClientCertificate().get())) {
                  updatedRegistrar.setFailoverClientCertificate(
                      registrar.get().getFailoverClientCertificate().get(),
                      tm().getTransactionTime());
                }

                tm().put(updatedRegistrar.build());
              });

      response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
    } catch (RegistrarAccessDeniedException e) {
      response.setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      response.setPayload(e.getMessage());
    }
  }

  private boolean validateCertificate(
      Optional<String> existingCertificate, String certificateString) {
    if (!existingCertificate.isPresent() || !existingCertificate.get().equals(certificateString)) {
      try {
        certificateChecker.validateCertificate(certificateString);
      } catch (InsecureCertificateException e) {
        throw new IllegalArgumentException(e.getMessage());
      }
      return true;
    }
    return false;
  }
}
