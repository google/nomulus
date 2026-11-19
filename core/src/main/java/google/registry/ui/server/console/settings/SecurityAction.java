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
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import google.registry.flows.certs.CertificateChecker;
import google.registry.flows.certs.CertificateChecker.InsecureCertificateException;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.OptionalJsonPayload;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.StringJoiner;

@Action(
    service = Service.CONSOLE,
    path = SecurityAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class SecurityAction extends ConsoleApiAction {

  static final String PATH = "/console-api/settings/security";
  private final String registrarId;
  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final Optional<JsonElement> payload;
  private final CertificateChecker certificateChecker;

  @Inject
  public SecurityAction(
      ConsoleApiParams consoleApiParams,
      CertificateChecker certificateChecker,
      AuthenticatedRegistrarAccessor registrarAccessor,
      @Parameter("registrarId") String registrarId,
      @OptionalJsonPayload Optional<JsonElement> payload) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.registrarAccessor = registrarAccessor;
    this.payload = payload;
    this.certificateChecker = certificateChecker;
  }

  @Override
  protected void postHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.EDIT_REGISTRAR_DETAILS);
    checkArgument(payload.isPresent(), "'registrar' parameter is not present");
    checkArgument(payload.get().isJsonObject(), "'registrar' parameter must be a JSON object");

    Registrar savedRegistrar;
    try {
      savedRegistrar = registrarAccessor.getRegistrar(registrarId);
    } catch (RegistrarAccessDeniedException e) {
      setFailedResponse(e.getMessage(), SC_FORBIDDEN);
      return;
    }

    tm().transact(() -> setResponse(savedRegistrar));
  }

  private void setResponse(Registrar savedRegistrar) {
    JsonObject jsonObject = payload.get().getAsJsonObject();
    Registrar.Builder updatedRegistrarBuilder = savedRegistrar.asBuilder();
    StringJoiner updates = new StringJoiner(",");

    boolean hasHostnames = isFieldPresent(jsonObject, "allowedEppClientCertificateHostnames");
    boolean hasRevokedHashes = isFieldPresent(jsonObject, "revokedClientCertificateHashes");
    try {
      if (hasHostnames) {
        checkStringArrayElements(
            jsonObject,
            "allowedEppClientCertificateHostnames",
            "EPP client certificate hostname cannot be null");
      }
      if (hasRevokedHashes) {
        checkStringArrayElements(
            jsonObject,
            "revokedClientCertificateHashes",
            "Revoked client certificate hash cannot be null");
      }
      Registrar registrarParameter = consoleApiParams.gson().fromJson(jsonObject, Registrar.class);

      if (!savedRegistrar
          .getIpAddressAllowList()
          .equals(registrarParameter.getIpAddressAllowList())) {
        updatedRegistrarBuilder.setIpAddressAllowList(registrarParameter.getIpAddressAllowList());
        updates.add("IP_CHANGE");
      }
      if (hasHostnames) {
        checkArgument(
            !registrarParameter.getAllowedEppClientCertificateHostnames().isEmpty()
                || savedRegistrar.getAllowedEppClientCertificateHostnames().isEmpty(),
            "Cannot clear allowed EPP client certificate hostnames once configured");
        updatedRegistrarBuilder.setAllowedEppClientCertificateHostnames(
            registrarParameter.getAllowedEppClientCertificateHostnames());
      }
      if (hasRevokedHashes) {
        updatedRegistrarBuilder.setRevokedClientCertificateHashes(
            registrarParameter.getRevokedClientCertificateHashes());
      }
      if (!savedRegistrar
          .getClientCertificate()
          .equals(registrarParameter.getClientCertificate())) {
        if (registrarParameter.getClientCertificate().isPresent()) {
          String newClientCert = registrarParameter.getClientCertificate().get();
          certificateChecker.validateCertificate(newClientCert);
          updatedRegistrarBuilder.setClientCertificate(newClientCert, tm().getTxTime());
          updates.add("PRIMARY_SSL_CERT_CHANGE");
        }
      }
      if (!savedRegistrar
          .getFailoverClientCertificate()
          .equals(registrarParameter.getFailoverClientCertificate())) {
        if (registrarParameter.getFailoverClientCertificate().isPresent()) {
          String newFailoverCert = registrarParameter.getFailoverClientCertificate().get();
          certificateChecker.validateCertificate(newFailoverCert);
          updatedRegistrarBuilder.setFailoverClientCertificate(newFailoverCert, tm().getTxTime());
          updates.add("FAILOVER_SSL_CERT_CHANGE");
        }
      }
    } catch (InsecureCertificateException | IllegalArgumentException | JsonSyntaxException e) {
      setFailedResponse(e.getMessage(), SC_BAD_REQUEST);
      return;
    }

    Registrar updatedRegistrar = updatedRegistrarBuilder.build();
    if (!savedRegistrar
        .getAllowedEppClientCertificateHostnames()
        .equals(updatedRegistrar.getAllowedEppClientCertificateHostnames())) {
      updates.add("EPP_CERT_HOSTNAMES_CHANGE");
    }
    if (!savedRegistrar
        .getRevokedClientCertificateHashes()
        .equals(updatedRegistrar.getRevokedClientCertificateHashes())) {
      updates.add("REVOKED_CERTS_CHANGE");
    }
    tm().put(updatedRegistrar);
    finishAndPersistConsoleUpdateHistory(
        new ConsoleUpdateHistory.Builder()
            .setType(ConsoleUpdateHistory.Type.REGISTRAR_SECURITY_UPDATE)
            .setDescription(
                String.format(
                    "%s%s%s", registrarId, ConsoleUpdateHistory.DESCRIPTION_SEPARATOR, updates)));

    sendExternalUpdatesIfNecessary(
        EmailInfo.create(savedRegistrar, updatedRegistrar, ImmutableSet.of(), ImmutableSet.of()));
    consoleApiParams.response().setStatus(SC_OK);
  }

  private static boolean isFieldPresent(JsonObject jsonObject, String fieldName) {
    return jsonObject.has(fieldName) && !jsonObject.get(fieldName).isJsonNull();
  }

  private static void checkStringArrayElements(
      JsonObject jsonObject, String fieldName, String nullElementErrorMessage) {
    JsonElement field = jsonObject.get(fieldName);
    checkArgument(field.isJsonArray(), "'%s' must be a JSON array", fieldName);
    for (JsonElement element : field.getAsJsonArray()) {
      checkArgument(!element.isJsonNull(), nullElementErrorMessage);
      checkArgument(
          element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
          "'%s' elements must be strings",
          fieldName);
    }
  }
}
