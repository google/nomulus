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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import com.google.common.collect.ImmutableList;
import com.google.gson.annotations.Expose;
import google.registry.flows.EppException;
import google.registry.flows.domain.DomainFlowUtils;
import google.registry.groups.GmailClient;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.domain.RegistryLock;
import google.registry.model.registrar.Registrar;
import google.registry.model.tld.RegistryLockDao;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.tools.DomainLockUtils;
import google.registry.util.EmailMessage;
import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.Duration;

/**
 * Handler for retrieving / creating registry lock requests in the console.
 *
 * <p>Note: two-factor verification of the locks occurs separately (TODO: link the verification
 * action).
 */
@Action(
    service = Service.CONSOLE,
    path = ConsoleRegistryLockAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ConsoleRegistryLockAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-lock";
  static final String VERIFICATION_EMAIL_TEMPLATE =
      """
      Please click the link below to perform the lock / unlock action on domain %s. Note: this\
       code will expire in one hour.

      %s\
      """;

  private final DomainLockUtils domainLockUtils;
  private final GmailClient gmailClient;
  private final Optional<ConsoleRegistryLockPostInput> optionalPostInput;
  private final String registrarId;

  @Inject
  public ConsoleRegistryLockAction(
      ConsoleApiParams consoleApiParams,
      DomainLockUtils domainLockUtils,
      GmailClient gmailClient,
      @Parameter("consoleRegistryLockPostInput")
          Optional<ConsoleRegistryLockPostInput> optionalPostInput,
      @Parameter("registrarId") String registrarId) {
    super(consoleApiParams);
    this.domainLockUtils = domainLockUtils;
    this.gmailClient = gmailClient;
    this.optionalPostInput = optionalPostInput;
    this.registrarId = registrarId;
  }

  @Override
  protected void getHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.REGISTRY_LOCK);
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(getLockedDomains()));
    consoleApiParams.response().setStatus(SC_OK);
  }

  @Override
  protected void postHandler(User user) {
    Response response = consoleApiParams.response();
    // User must have the proper permission on the registrar
    checkPermission(user, registrarId, ConsolePermission.REGISTRY_LOCK);

    // Shouldn't happen, but double-check the registrar has registry lock enabled
    Registrar registrar = Registrar.loadByRegistrarIdCached(registrarId).get();
    checkArgument(
        registrar.isRegistryLockAllowed(),
        "Registry lock not allowed for registrar %s",
        registrarId);

    // Retrieve and validate the necessary params
    ConsoleRegistryLockPostInput postInput =
        optionalPostInput.orElseThrow(() -> new IllegalArgumentException("No POST input provided"));
    String domainName = postInput.domainName();
    boolean isLock = postInput.isLock();
    Optional<String> maybePassword = Optional.ofNullable(postInput.password());
    Optional<Long> relockDurationMillis = Optional.ofNullable(postInput.relockDurationMillis());

    try {
      DomainFlowUtils.validateDomainName(domainName);
    } catch (EppException e) {
      throw new IllegalArgumentException(e);
    }

    // Passwords aren't required for admin users, otherwise we need to validate it
    boolean isAdmin = user.getUserRoles().isAdmin();
    if (!isAdmin) {
      checkArgument(maybePassword.isPresent(), "No password provided");
      if (!user.verifyRegistryLockPassword(maybePassword.get())) {
        setFailedResponse("Incorrect registry lock password", SC_UNAUTHORIZED);
        return;
      }
    }

    String registryLockEmail =
        user.getRegistryLockEmailAddress()
            .orElseThrow(
                () -> new IllegalArgumentException("User has no registry lock email address"));
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
    response.setStatus(SC_OK);
  }

  private void sendVerificationEmail(RegistryLock lock, String userEmail, boolean isLock) {
    try {
      String url =
          String.format(
              "https://%s/console/#/registry-lock-verify?lockVerificationCode=%s",
              consoleApiParams.request().getServerName(), lock.getVerificationCode());
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
    } catch (AddressException e) {
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

  public record ConsoleRegistryLockPostInput(
      @Expose String domainName,
      @Expose boolean isLock,
      @Expose @Nullable String password,
      @Expose @Nullable Long relockDurationMillis) {}
}
