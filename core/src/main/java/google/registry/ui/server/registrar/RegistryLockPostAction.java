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
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.security.JsonResponseHelper.Status.ERROR;
import static google.registry.security.JsonResponseHelper.Status.SUCCESS;
import static google.registry.ui.server.registrar.RegistrarConsoleModule.PARAM_CLIENT_ID;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.MoreCollectors;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.DomainBase;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.RegistryLockDao;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.JsonActionRunner;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.UserAuthInfo;
import google.registry.schema.domain.RegistryLock;
import google.registry.security.JsonResponseHelper;
import google.registry.util.Clock;
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
public final class RegistryLockPostAction implements Runnable, JsonActionRunner.JsonAction {

  public static final String PATH = "/registry-lock-post";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Gson GSON = new Gson();

  private static final URL URL_BASE = RegistryConfig.getDefaultServer();
  private static final String VERIFICATION_EMAIL_TEMPLATE =
      "Please click the link below to perform the %s on domain %s. Note: this code will expire "
          + "in one hour.\n\n%s";

  private final JsonActionRunner jsonActionRunner;
  @VisibleForTesting AuthResult authResult;
  private final AuthenticatedRegistrarAccessor registrarAccessor;
  private final SendEmailService sendEmailService;
  private final Clock clock;
  private final InternetAddress gSuiteOutgoingEmailAddress;

  @Inject
  RegistryLockPostAction(
      JsonActionRunner jsonActionRunner,
      AuthResult authResult,
      AuthenticatedRegistrarAccessor registrarAccessor,
      SendEmailService sendEmailService,
      Clock clock,
      @Config("gSuiteOutgoingEmailAddress") InternetAddress gSuiteOutgoingEmailAddress) {
    this.jsonActionRunner = jsonActionRunner;
    this.authResult = authResult;
    this.registrarAccessor = registrarAccessor;
    this.sendEmailService = sendEmailService;
    this.clock = clock;
    this.gSuiteOutgoingEmailAddress = gSuiteOutgoingEmailAddress;
  }

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  public Map<String, ?> handleJsonRequest(Map<String, ?> input) {
    try {
      checkArgument(input != null, "Null JSON");
      RegistryLockPostInput postInput =
          GSON.fromJson(GSON.toJsonTree(input), RegistryLockPostInput.class);
      checkArgument(
          !Strings.isNullOrEmpty(postInput.clientId),
          "Missing key for client: %s",
          PARAM_CLIENT_ID);

      verifyRegistryLockPassword(postInput);
      RegistryLock registryLock =
          jpaTm()
              .transact(
                  () -> {
                    RegistryLock lock = createLock(postInput);
                    RegistryLockDao.save(lock);
                    return lock;
                  });
      sendVerificationEmail(registryLock);
      String action = registryLock.getAction().equals(RegistryLock.Action.LOCK) ? "lock" : "unlock";
      return JsonResponseHelper.create(SUCCESS, String.format("Successful %s", action));
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log("Failed to lock/unlock domain with input %s", input);
      return JsonResponseHelper.create(
          ERROR, Optional.ofNullable(e.getMessage()).orElse("Unspecified error"));
    }
  }

  private RegistryLock createLock(RegistryLockPostInput postInput) {
    checkArgument(
        !Strings.isNullOrEmpty(postInput.fullyQualifiedDomainName),
        "Missing key for fullyQualifiedDomainName");

    DomainBase domainBase =
        loadByForeignKeyCached(DomainBase.class, postInput.fullyQualifiedDomainName, clock.nowUtc())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Unknown domain %s", postInput.fullyQualifiedDomainName)));

    // Multiple pending actions are not allowed
    Optional<RegistryLock> previousLock =
        RegistryLockDao.getMostRecentByRepoId(domainBase.getRepoId());
    previousLock.ifPresent(
        lock ->
            checkArgument(
                lock.isVerified()
                    || isBeforeOrAt(lock.getCreationTimestamp(), clock.nowUtc().minusHours(1)),
                String.format(
                    "A pending action already exists for %s", postInput.fullyQualifiedDomainName)));

    checkArgument(postInput.isLock != null, "Missing key for isLock");
    boolean isAdmin = authResult.userAuthInfo().get().isUserAdmin();

    // Unlock actions have restrictions (unless the user is admin)
    if (!postInput.isLock && !isAdmin) {
      RegistryLock previouslyVerifiedLock =
          previousLock
              .flatMap(
                  lock ->
                      lock.isVerified()
                          ? Optional.of(lock)
                          : RegistryLockDao.getMostRecentVerifiedLockByRepoId(
                              domainBase.getRepoId()))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Cannot unlock a domain without a previously-verified lock"));
      checkArgument(
          previouslyVerifiedLock.getAction().equals(RegistryLock.Action.LOCK),
          "Cannot unlock a domain multiple times");
      checkArgument(
          !previouslyVerifiedLock.isSuperuser(),
          "Non-admin user cannot unlock an admin-locked domain");
    }
    return new RegistryLock.Builder()
        .isSuperuser(isAdmin)
        .setVerificationCode(UUID.randomUUID().toString())
        .setAction(postInput.isLock ? RegistryLock.Action.LOCK : RegistryLock.Action.UNLOCK)
        .setDomainName(postInput.fullyQualifiedDomainName)
        .setRegistrarId(postInput.clientId)
        .setRepoId(domainBase.getRepoId())
        .setRegistrarPocId(authResult.userAuthInfo().get().user().getEmail())
        .build();
  }

  private void sendVerificationEmail(RegistryLock lock) throws AddressException {
    String url =
        URL_BASE.toString()
            + "/registry-lock-verify" // RegistryLockVerifyAction.PATH
            + String.format("?lockVerificationCode=%s", lock.getVerificationCode());
    String action = lock.getAction().equals(RegistryLock.Action.LOCK) ? "lock" : "unlock";
    String body = String.format(VERIFICATION_EMAIL_TEMPLATE, action, lock.getDomainName(), url);
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

  private void verifyRegistryLockPassword(RegistryLockPostInput postInput)
      throws RegistrarAccessDeniedException {
    // Verify that the user can access the registrar and that the user is either an admin or has
    // registry lock enabled and provided a correct password
    checkArgument(authResult.userAuthInfo().isPresent(), "Auth result not present");
    Registrar registrar = registrarAccessor.getRegistrar(postInput.clientId);
    checkArgument(
        registrar.isRegistryLockAllowed(), "Registry lock not allowed for this registrar");
    UserAuthInfo userAuthInfo = authResult.userAuthInfo().get();
    if (!userAuthInfo.isUserAdmin()) {
      checkArgument(!Strings.isNullOrEmpty(postInput.password), "Missing key for password");
      RegistrarContact registrarContact =
          registrar.getContacts().stream()
              .filter(contact -> contact.getEmailAddress().equals(userAuthInfo.user().getEmail()))
              .collect(MoreCollectors.onlyElement());
      checkArgument(
          registrarContact.verifyRegistryLockPassword(postInput.password),
          "Incorrect registry lock password for contact");
    }
  }

  /** Value class that represents the expected input body from the UI request. */
  private static class RegistryLockPostInput {
    private String clientId;
    private String fullyQualifiedDomainName;
    private Boolean isLock;
    private String password;
  }
}
