// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.security.JsonResponseHelper.Status.ERROR;
import static google.registry.security.JsonResponseHelper.Status.SUCCESS;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.MoreCollectors;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.JsonActionRunner;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.UserAuthInfo;
import google.registry.security.JsonResponseHelper;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

/**
 * Admin servlet that allows for updating registrar locks for a particular registrar. Locks /
 * unlocks must be verified separately before they are written permanently.
 *
 * <p>Note: at the moment we have no mechanism for JSON GET/POSTs in the same class or at the same *
 * URL, which is why this is distinct from the {@link RegistryLockGetAction}.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistryLockPostAction.PATH,
    method = Method.POST,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public final class RegistryLockPostAction implements Runnable, JsonActionRunner.JsonAction {

  public static final String PATH = "/registry-lock-post";

  private static final URL URL_BASE = RegistryConfig.getDefaultServer();
  private static final String VERIFICATION_EMAIL_TEMPLATE =
      "Please click the link below to perform the %s on domain %s. Note: this code will expire "
          + "in one hour.\n\n%s";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String PARAM_FQDN = "fullyQualifiedDomainName";
  private static final String PARAM_IS_LOCK = "isLock";
  private static final String PARAM_PASSWORD = "password";

  private final ExistingRegistryLocksRetriever existingRegistryLocksRetriever;
  private final JsonActionRunner jsonActionRunner;
  @VisibleForTesting AuthResult authResult;
  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final SendEmailService sendEmailService;
  private final InternetAddress gSuiteOutgoingEmailAddress;

  @Inject
  RegistryLockPostAction(
      ExistingRegistryLocksRetriever existingRegistryLocksRetriever,
      JsonActionRunner jsonActionRunner,
      AuthResult authResult,
      AuthenticatedRegistrarAccessor registrarAccessor,
      SendEmailService sendEmailService,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress) {
    this.existingRegistryLocksRetriever = existingRegistryLocksRetriever;
    this.jsonActionRunner = jsonActionRunner;
    this.authResult = authResult;
    this.registrarAccessor = registrarAccessor;
    this.sendEmailService = sendEmailService;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
  }

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  public Map<String, ?> handleJsonRequest(Map<String, ?> input) {
    try {
      checkArgument(input != null, "Malformed JSON");
      String clientId = (String) input.get(PARAM_CLIENT_ID);
      checkArgument(
          !Strings.isNullOrEmpty(clientId), "Missing key for client: %s", PARAM_CLIENT_ID);

      String domainName = (String) input.get(PARAM_FQDN);
      checkArgument(!Strings.isNullOrEmpty(domainName), "Missing key for fqdn: %s", PARAM_FQDN);

      checkArgument(input.containsKey(PARAM_IS_LOCK), "Missing key for isLock: %s", PARAM_IS_LOCK);
      boolean lock = (Boolean) input.get(PARAM_IS_LOCK);

      verifyRegistryLockPassword(clientId, (String) input.get(PARAM_PASSWORD));
      logger.atInfo().log(
          String.format("Performing action %s to domain %s", lock ? "lock" : "unlock", domainName));
      UUID randomUuid = UUID.randomUUID();
      // TODO: do the lock here once we can
      sendVerificationEmail(lock, randomUuid, domainName);
      return JsonResponseHelper.create(
          SUCCESS,
          "Successful lock / unlock",
          existingRegistryLocksRetriever.getLockedDomainsMap(clientId));
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log("Failed to lock/unlock domain with input %s", input);
      return JsonResponseHelper.create(
          ERROR, Optional.ofNullable(e.getMessage()).orElse("Unspecified error"));
    }
  }

  private void sendVerificationEmail(boolean lock, UUID randomUuid, String domainName)
      throws AddressException {
    String url =
        URL_BASE.toString()
            + RegistryLockVerifyAction.PATH
            + String.format("?lockId=%s", randomUuid);
    String action = lock ? "lock" : "unlock";
    String body = String.format(VERIFICATION_EMAIL_TEMPLATE, action, domainName, url);
    String subject = String.format("Registry %s verification", action);
    ImmutableList<InternetAddress> recipients =
        ImmutableList.of(
            new InternetAddress(authResult.userAuthInfo().get().user().getEmail(), true));
    sendEmailService.sendEmail(
        EmailMessage.newBuilder()
            .setBody(body)
            .setSubject(subject)
            .setRecipients(recipients)
            .setFrom(gSuiteOutgoingEmailAddress)
            .build());
  }

  private void verifyRegistryLockPassword(String clientId, String password)
      throws RegistrarAccessDeniedException {
    // Verify that the user can access the registrar and that the user is either an admin or has
    // registry lock enabled and provided a correct password
    checkArgument(authResult.userAuthInfo().isPresent(), "Auth result not present");
    Registrar registrar = registrarAccessor.getRegistrar(clientId);
    checkArgument(
        registrar.isRegistryLockAllowed(), "Registry lock not allowed for this registrar");
    UserAuthInfo userAuthInfo = authResult.userAuthInfo().get();
    if (!userAuthInfo.isUserAdmin()) {
      checkArgument(
          !Strings.isNullOrEmpty(password), "Missing key for password: %s", PARAM_PASSWORD);
      RegistrarContact registrarContact =
          registrar.getContacts().stream()
              .filter(contact -> contact.getEmailAddress().equals(userAuthInfo.user().getEmail()))
              .collect(MoreCollectors.onlyElement());
      checkArgument(
          registrarContact.verifyRegistryLockPassword(password),
          "Incorrect registry lock password for contact");
    }
  }
}
