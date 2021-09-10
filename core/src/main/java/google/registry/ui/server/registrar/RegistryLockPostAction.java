// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.security.JsonResponseHelper.Status.ERROR;
import static google.registry.security.JsonResponseHelper.Status.SUCCESS;
import static google.registry.ui.server.registrar.RegistryLockGetAction.getContactMatchingLogin;
import static google.registry.ui.server.registrar.RegistryLockGetAction.getRegistrarAndVerifyLockAccess;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.appengine.api.users.User;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.model.domain.RegistryLock;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.HttpException.ForbiddenException;
import google.registry.request.JsonActionRunner;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.UserAuthInfo;
import google.registry.security.JsonResponseHelper;
import google.registry.tools.DomainLockUtils;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletRequest;
import org.apache.http.client.utils.URIBuilder;
import org.joda.time.Duration;

/**
 * UI action that allows for creating registry locks. Locks / unlocks must be verified separately
 * before they are written permanently.
 *
 * <p>Note: at the moment we have no mechanism for JSON GET/POSTs in the same class or at the same
 * URL, which is why this is distinct from the {@link RegistryLockGetAction}.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistryLockPostAction.PATH,
    method = Method.POST,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryLockPostAction implements Runnable, JsonActionRunner.JsonAction {
  public static final String PATH = "/registry-lock-post";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson GSON = new Gson();

  private static final String VERIFICATION_EMAIL_TEMPLATE =
      "Please click the link below to perform the lock / unlock action on domain %s. Note: "
          + "this code will expire in one hour.\n\n%s";

  private final HttpServletRequest req;
  private final JsonActionRunner jsonActionRunner;
  private final AuthResult authResult;
  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final SendEmailService sendEmailService;
  private final DomainLockUtils domainLockUtils;
  private final InternetAddress gSuiteOutgoingEmailAddress;

  @Inject
  RegistryLockPostAction(
      HttpServletRequest req,
      JsonActionRunner jsonActionRunner,
      AuthResult authResult,
      AuthenticatedRegistrarAccessor registrarAccessor,
      SendEmailService sendEmailService,
      DomainLockUtils domainLockUtils,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress) {
    this.req = req;
    this.jsonActionRunner = jsonActionRunner;
    this.authResult = authResult;
    this.registrarAccessor = registrarAccessor;
    this.sendEmailService = sendEmailService;
    this.domainLockUtils = domainLockUtils;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
  }

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  public Map<String, ?> handleJsonRequest(Map<String, ?> input) {
    try {
      checkArgumentNotNull(input, "Null JSON");
      RegistryLockPostInput postInput =
          GSON.fromJson(GSON.toJsonTree(input), RegistryLockPostInput.class);
      String registrarId = postInput.registrarId;
      checkArgument(!Strings.isNullOrEmpty(registrarId), "Missing key for registrarId");
      checkArgument(!Strings.isNullOrEmpty(postInput.domainName), "Missing key for domainName");
      DomainFlowUtils.validateDomainName(postInput.domainName);
      checkNotNull(postInput.isLock, "Missing key for isLock");
      UserAuthInfo userAuthInfo =
          authResult
              .userAuthInfo()
              .orElseThrow(() -> new ForbiddenException("User is not logged in"));

      String userEmail = verifyPasswordAndGetEmail(userAuthInfo, postInput);
      jpaTm()
          .transact(
              () -> {
                RegistryLock registryLock =
                    postInput.isLock
                        ? domainLockUtils.saveNewRegistryLockRequest(
                            postInput.domainName,
                            registrarId,
                            userEmail,
                            registrarAccessor.isAdmin())
                        : domainLockUtils.saveNewRegistryUnlockRequest(
                            postInput.domainName,
                            registrarId,
                            registrarAccessor.isAdmin(),
                            Optional.ofNullable(postInput.relockDurationMillis).map(Duration::new));
                sendVerificationEmail(registryLock, userEmail, postInput.isLock);
              });
      String action = postInput.isLock ? "lock" : "unlock";
      return JsonResponseHelper.create(SUCCESS, String.format("Successful %s", action));
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log("Failed to lock/unlock domain");
      return JsonResponseHelper.create(
          ERROR,
          Optional.ofNullable(Throwables.getRootCause(e).getMessage()).orElse("Unspecified error"));
    }
  }

  private void sendVerificationEmail(RegistryLock lock, String userEmail, boolean isLock) {
    try {
      String url =
          new URIBuilder()
              .setScheme("https")
              .setHost(req.getServerName())
              .setPath("registry-lock-verify")
              .setParameter("lockVerificationCode", lock.getVerificationCode())
              .setParameter("isLock", String.valueOf(isLock))
              .build()
              .toString();
      String body = String.format(VERIFICATION_EMAIL_TEMPLATE, lock.getDomainName(), url);
      ImmutableList<InternetAddress> recipients =
          ImmutableList.of(new InternetAddress(userEmail, true));
      String action = isLock ? "lock" : "unlock";
      sendEmailService.sendEmail(
          EmailMessage.newBuilder()
              .setBody(body)
              .setSubject(String.format("Registry %s verification", action))
              .setRecipients(recipients)
              .setFrom(gSuiteOutgoingEmailAddress)
              .build());
    } catch (AddressException | URISyntaxException e) {
      throw new RuntimeException(e); // caught above -- this is so we can run in a transaction
    }
  }

  private String verifyPasswordAndGetEmail(
      UserAuthInfo userAuthInfo, RegistryLockPostInput postInput)
      throws RegistrarAccessDeniedException {
    User user = userAuthInfo.user();
    if (registrarAccessor.isAdmin()) {
      return user.getEmail();
    }
    // Verify that the user can access the registrar, that the user has
    // registry lock enabled, and that the user provided a correct password
    Registrar registrar =
        getRegistrarAndVerifyLockAccess(registrarAccessor, postInput.registrarId, false);
    RegistrarContact registrarContact =
        getContactMatchingLogin(user, registrar)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "Cannot match user %s to registrar contact", user.getUserId())));
    checkArgument(
        registrarContact.verifyRegistryLockPassword(postInput.password),
        "Incorrect registry lock password for contact");
    return registrarContact
        .getRegistryLockEmailAddress()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    String.format(
                        "Contact %s had no registry lock email address",
                        registrarContact.getEmailAddress())));
  }

  /** Value class that represents the expected input body from the UI request. */
  private static class RegistryLockPostInput {
    private String registrarId;
    private String domainName;
    private Boolean isLock;
    private String password;
    private Long relockDurationMillis;
  }
}
