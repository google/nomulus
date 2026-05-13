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

import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;

import com.google.api.services.gmail.Gmail;
import com.google.common.flogger.FluentLogger;
import dagger.Lazy;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GmailClient;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Action that executes a canned script specified by the caller.
 *
 * <p>This class provides a hook for invoking hard-coded methods. The main use case is to verify in
 * Sandbox and Production environments new features that depend on environment-specific
 * configurations.
 *
 * <p>This action can be invoked using the Nomulus CLI command: {@code nomulus -e ${env} curl
 * --service BACKEND -X POST -d 'sender=sender@example.com' -d 'receiver=receiver@example.com' -u
 * '/_dr/task/executeCannedScript'}
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/executeCannedScript",
    method = {POST, GET},
    automaticallyPrintOk = true,
    auth = Auth.AUTH_ADMIN)
public class CannedScriptExecutionAction implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Lazy<Gmail> gmail;
  @Inject Retrier retrier;

  @Inject
  @Config("isEmailSendingEnabled")
  boolean isEmailSendingEnabled;

  @Inject Response response;

  @Inject
  @Parameter("sender")
  String sender;

  @Inject
  @Parameter("receiver")
  String receiver;

  @Inject
  CannedScriptExecutionAction() {}

  @Override
  public void run() {
    // For b/510340944, validating a new G Workspace user can send email. Code below can be
    // removed or changed afterward.
    try {
      logger.atInfo().log("Sending email from %s to %s", sender, receiver);
      GmailClient gmailClient =
          new GmailClient(
              gmail, retrier, isEmailSendingEnabled, sender, sender, new InternetAddress(sender));
      gmailClient.sendEmail(
          EmailMessage.newBuilder()
              .addRecipient(new InternetAddress(receiver))
              .setSubject(String.format("Email send test from %s", sender))
              .setBody(String.format("This is a test email sent from %s to %s.", sender, receiver))
              .build());
      response.setPayload("Email sent successfully.");
    } catch (AddressException e) {
      logger.atWarning().withCause(e).log(
          "Invalid email address: sender=%s, receiver=%s", sender, receiver);
      response.setStatus(400);
      response.setPayload("Invalid email address provided.");
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Failed to send email");
      throw new RuntimeException(e);
    }
  }
}
