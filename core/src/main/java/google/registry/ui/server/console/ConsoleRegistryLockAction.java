// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.extractBooleanParameter;
import static google.registry.request.RequestParameters.extractOptionalLongParameter;
import static google.registry.request.RequestParameters.extractOptionalParameter;
import static google.registry.request.RequestParameters.extractRequiredParameter;
import static google.registry.ui.server.registrar.RegistryLockPostAction.VERIFICATION_EMAIL_TEMPLATE;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import google.registry.flows.EppException;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.groups.GmailClient;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.domain.RegistryLock;
import google.registry.model.registrar.Registrar;
import google.registry.model.tld.RegistryLockDao;
import google.registry.request.Action;
import google.registry.request.HttpException;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.tools.DomainLockUtils;
import google.registry.ui.server.registrar.ConsoleApiParams;
import google.registry.util.EmailMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URISyntaxException;
import java.util.Optional;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.apache.http.client.utils.URIBuilder;
import org.joda.time.Duration;

/**
 * Handler for retrieving / creating registry lock requests in the console.
 *
 * <p>Note: two-factor verification of the locks occurs separately (TODO: link the verification
 * action).
 */
@Action(
    service = Action.Service.DEFAULT,
    path = ConsoleRegistryLockAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleRegistryLockAction extends ConsoleApiAction {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String PATH = "/console-api/registry-lock";

  private final DomainLockUtils domainLockUtils;
  private final GmailClient gmailClient;
  private final Gson gson;
  private final String registrarId;

  @Inject
  public ConsoleRegistryLockAction(
      ConsoleApiParams consoleApiParams,
      DomainLockUtils domainLockUtils,
      GmailClient gmailClient,
      Gson gson,
      @Parameter("registrarId") String registrarId) {
    super(consoleApiParams);
    this.domainLockUtils = domainLockUtils;
    this.gmailClient = gmailClient;
    this.gson = gson;
    this.registrarId = registrarId;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasPermission(registrarId, ConsolePermission.REGISTRY_LOCK)) {
      consoleApiParams.response().setStatus(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }
    consoleApiParams.response().setPayload(gson.toJson(getLockedDomains()));
    consoleApiParams.response().setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }

  @Override
  protected void postHandler(User user) {
    HttpServletRequest req = consoleApiParams.request();
    Response response = consoleApiParams.response();
    // User must have the proper permission on the registrar
    if (!user.getUserRoles().hasPermission(registrarId, ConsolePermission.REGISTRY_LOCK)) {
      setFailedResponse("", HttpStatusCodes.STATUS_CODE_FORBIDDEN);
      return;
    }

    // Shouldn't happen, but double-check the registrar has registry lock enabled
    Registrar registrar = Registrar.loadByRegistrarIdCached(registrarId).get();
    if (!registrar.isRegistryLockAllowed()) {
      setFailedResponse(
          String.format("Registry lock not allowed for registrar %s", registrarId),
          HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      return;
    }

    // Retrieve and validate the necessary params
    String domainName;
    boolean isLock;
    Optional<String> maybePassword;
    Optional<Long> relockDurationMillis;

    try {
      domainName = extractRequiredParameter(req, "domainName");
      isLock = extractBooleanParameter(req, "isLock");
      maybePassword = extractOptionalParameter(req, "password");
      relockDurationMillis = extractOptionalLongParameter(req, "relockDurationMillis");
      DomainFlowUtils.validateDomainName(domainName);
    } catch (HttpException.BadRequestException | EppException e) {
      logger.atWarning().withCause(e).log("Bad request when attempting registry lock/unlock");
      setFailedResponse(e.getMessage(), HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      return;
    }

    // Passwords aren't required for admin users, otherwise we need to validate it
    boolean isAdmin = user.getUserRoles().isAdmin();
    if (!isAdmin) {
      if (maybePassword.isEmpty()) {
        setFailedResponse("No password provided", HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
        return;
      }
      if (!user.verifyRegistryLockPassword(maybePassword.get())) {
        setFailedResponse(
            "Incorrect registry lock password", HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
        return;
      }
    }

    Optional<String> maybeRegistryLockEmail = user.getRegistryLockEmailAddress();
    if (maybeRegistryLockEmail.isEmpty()) {
      setFailedResponse(
          "User has no registry lock email address", HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      return;
    }
    String registryLockEmail = maybeRegistryLockEmail.get();

    try {
      tm().transact(
              () -> {
                RegistryLock registryLock =
                    isLock
                        ? domainLockUtils.saveNewRegistryLockRequest(
                            domainName, registrarId, registryLockEmail, isAdmin)
                        : domainLockUtils.saveNewRegistryUnlockRequest(
                            domainName,
                            registrarId,
                            isAdmin,
                            relockDurationMillis.map(Duration::new));
                sendVerificationEmail(registryLock, registryLockEmail, isLock);
              });
    } catch (IllegalArgumentException e) {
      // Catch IllegalArgumentExceptions separately to give a nicer error message and code
      logger.atWarning().withCause(e).log("Failed to lock/unlock domain");
      setFailedResponse(e.getMessage(), HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
      return;
    } catch (Throwable t) {
      logger.atWarning().withCause(t).log("Failed to lock/unlock domain");
      setFailedResponse("Internal server error", HttpStatusCodes.STATUS_CODE_SERVER_ERROR);
      return;
    }
    response.setStatus(HttpStatusCodes.STATUS_CODE_OK);
  }

  private void sendVerificationEmail(RegistryLock lock, String userEmail, boolean isLock) {
    try {
      String url =
          new URIBuilder()
              .setScheme("https")
              .setHost(consoleApiParams.request().getServerName())
              // TODO: replace this with the PATH in ConsoleRegistryLockVerifyAction once it exists
              .setPath("/console-api/registry-lock-verify")
              .setParameter("lockVerificationCode", lock.getVerificationCode())
              .build()
              .toString();
      String body = String.format(VERIFICATION_EMAIL_TEMPLATE, lock.getDomainName(), url);
      ImmutableList<InternetAddress> recipients =
          ImmutableList.of(new InternetAddress(userEmail, true));
      String action = isLock ? "lock" : "unlock";
      gmailClient.sendEmail(
          EmailMessage.newBuilder()
              .setBody(body)
              .setSubject(String.format("Registry %s verification", action))
              .setRecipients(recipients)
              .build());
    } catch (AddressException | URISyntaxException e) {
      throw new RuntimeException(e); // caught above -- this is so we can run in a transaction
    }
  }

  private ImmutableList<RegistryLock> getLockedDomains() {
    return tm().transact(
            () ->
                RegistryLockDao.getLocksByRegistrarId(registrarId).stream()
                    .filter(lock -> !lock.isLockRequestExpired(tm().getTransactionTime()))
                    .collect(toImmutableList()));
  }
}
