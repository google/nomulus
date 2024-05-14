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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;

import com.google.api.client.http.HttpStatusCodes;
import google.registry.flows.certs.CertificateChecker;
import google.registry.flows.certs.CertificateChecker.InsecureCertificateException;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.registrar.ConsoleApiParams;
import java.util.Optional;
import javax.inject.Inject;

@Action(
    service = Action.Service.DEFAULT,
    path = SecurityAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class SecurityAction extends ConsoleApiAction {

  static final String PATH = "/console-api/settings/security";
  private final String registrarId;
  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final Optional<Registrar> registrar;
  private final CertificateChecker certificateChecker;

  @Inject
  public SecurityAction(
      ConsoleApiParams consoleApiParams,
      CertificateChecker certificateChecker,
      AuthenticatedRegistrarAccessor registrarAccessor,
      @Parameter("registrarId") String registrarId,
      @Parameter("registrar") Optional<Registrar> registrar) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.registrarAccessor = registrarAccessor;
    this.registrar = registrar;
    this.certificateChecker = certificateChecker;
  }

  @Override
  protected void postHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.EDIT_REGISTRAR_DETAILS);
    checkArgument(registrar.isPresent(), "'registrar' parameter is not present");

    Registrar savedRegistrar;
    try {
      savedRegistrar = registrarAccessor.getRegistrar(registrarId);
    } catch (RegistrarAccessDeniedException e) {
      setFailedResponse(e.getMessage(), HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }

    tm().transact(() -> setResponse(savedRegistrar));
  }

  private void setResponse(Registrar savedRegistrar) {
    Registrar registrarParameter = registrar.get();
    Registrar.Builder updatedRegistrar =
        savedRegistrar
            .asBuilder()
            .setIpAddressAllowList(registrarParameter.getIpAddressAllowList());

    try {
      if (!savedRegistrar
          .getClientCertificate()
          .equals(registrarParameter.getClientCertificate())) {
        if (registrarParameter.getClientCertificate().isPresent()) {
          String newClientCert = registrarParameter.getClientCertificate().get();
          certificateChecker.validateCertificate(newClientCert);
          updatedRegistrar.setClientCertificate(newClientCert, tm().getTransactionTime());
        }
      }
      if (!savedRegistrar
          .getFailoverClientCertificate()
          .equals(registrarParameter.getFailoverClientCertificate())) {
        if (registrarParameter.getFailoverClientCertificate().isPresent()) {
          String newFailoverCert = registrarParameter.getFailoverClientCertificate().get();
          certificateChecker.validateCertificate(newFailoverCert);
          updatedRegistrar.setFailoverClientCertificate(newFailoverCert, tm().getTransactionTime());
        }
      }
    } catch (InsecureCertificateException e) {
      setFailedResponse(
          "Invalid certificate in parameter", HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      return;
    }

    tm().put(updatedRegistrar.build());
    consoleApiParams.response().setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }
}
