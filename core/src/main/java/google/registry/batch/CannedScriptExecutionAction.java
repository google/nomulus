// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.RegistrarUtils.normalizeRegistrarId;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GmailClient;
import google.registry.groups.GroupsConnection;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.request.Action;
import google.registry.request.auth.Auth;
import google.registry.util.EmailMessage;
import java.io.IOException;
import java.util.Set;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;

/**
 * Action that executes a canned script specified by the caller.
 *
 * <p>This class provides a hook for invoking hard-coded methods. The main use case is to verify in
 * Sandbox and Production environments new features that depend on environment-specific
 * configurations. For example, the {@code DelegatedCredential}, which requires correct GWorkspace
 * configuration, has been tested this way. Since it is a hassle to add or remove endpoints, we keep
 * this class all the time.
 *
 * <p>This action can be invoked using the Nomulus CLI command: {@code nomulus -e ${env} curl
 * --service BACKEND -X POST -u '/_dr/task/executeCannedScript}'}
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/executeCannedScript",
    method = POST,
    automaticallyPrintOk = true,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class CannedScriptExecutionAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final GroupsConnection groupsConnection;
  private final GmailClient gmailClient;

  private final InternetAddress senderAddress;

  private final InternetAddress recipientAddress;

  private final String gSuiteDomainName;

  @Inject
  CannedScriptExecutionAction(
      GroupsConnection groupsConnection,
      GmailClient gmailClient,
      @Config("projectId") String projectId,
      @Config("gSuiteDomainName") String gSuiteDomainName,
      @Config("alertRecipientEmailAddress") InternetAddress recipientAddress) {
    this.groupsConnection = groupsConnection;
    this.gmailClient = gmailClient;
    this.gSuiteDomainName = gSuiteDomainName;
    try {
      this.senderAddress = new InternetAddress(String.format("%s@%s", projectId, gSuiteDomainName));
    } catch (AddressException e) {
      throw new RuntimeException(e);
    }
    this.recipientAddress = recipientAddress;
    logger.atInfo().log("Sender:%s; Recipient: %s.", this.senderAddress, this.recipientAddress);
  }

  @Override
  public void run() {
    try {
      // Invoke canned scripts here.
      checkGroupApi();
      EmailMessage message = createEmail();
      this.gmailClient.sendEmail(message);
      logger.atInfo().log("Finished running scripts.");
    } catch (Throwable t) {
      logger.atWarning().withCause(t).log("Error executing scripts.");
      throw new RuntimeException("Execution failed.");
    }
  }

  // Checks if Directory and GroupSettings still work after GWorkspace changes.
  void checkGroupApi() {
    ImmutableList<Registrar> registrars =
        Streams.stream(Registrar.loadAllCached())
            .filter(registrar -> registrar.isLive() && registrar.getType() == Registrar.Type.REAL)
            .collect(toImmutableList());
    logger.atInfo().log("Found %s registrars.", registrars.size());
    for (Registrar registrar : registrars) {
      for (final RegistrarPoc.Type type : RegistrarPoc.Type.values()) {
        String groupKey =
            String.format(
                "%s-%s-contacts@%s",
                normalizeRegistrarId(registrar.getRegistrarId()),
                type.getDisplayName(),
                gSuiteDomainName);
        try {
          Set<String> currentMembers = groupsConnection.getMembersOfGroup(groupKey);
          logger.atInfo().log("%s has %s members.", groupKey, currentMembers.size());
        } catch (IOException e) {
          logger.atWarning().withCause(e).log("Failed to check %s", groupKey);
        }
      }
    }
    logger.atInfo().log("Finished checking GroupApis.");
  }

  EmailMessage createEmail() {
    return EmailMessage.newBuilder()
        .setFrom(senderAddress)
        .setSubject("Test: Please ignore<eom>.")
        .setRecipients(ImmutableList.of(recipientAddress))
        .setBody("Sent from Nomulus through Google Workspace.")
        .build();
  }
}
