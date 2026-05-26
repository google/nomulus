// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.spec11;

import static com.google.common.base.Throwables.getRootCause;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.QueryComposer.Comparator;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.config.RegistryConfig.Config;
import google.registry.groups.GmailClient;
import google.registry.model.domain.Domain;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.util.EmailMessage;
import google.registry.util.Sleeper;
import google.registry.util.TemplateRenderer;
import jakarta.inject.Inject;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/** Provides e-mail functionality for Spec11 tasks, such as sending Spec11 reports to registrars. */
public class Spec11EmailUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Enum of Spec11 email templates. */
  public enum Spec11EmailTemplate {
    DAILY("daily_spec11_email.ftl"),
    MONTHLY("monthly_spec11_email.ftl");

    private final String ftlPath;

    Spec11EmailTemplate(String ftlPath) {
      this.ftlPath = "google/registry/reporting/spec11/ftl/" + ftlPath;
    }

    public String getFtlPath() {
      return ftlPath;
    }
  }

  private final GmailClient gmailClient;
  private final Sleeper sleeper;
  private final TemplateRenderer templateRenderer;
  private final Duration emailThrottleDuration;
  private final InternetAddress outgoingEmailAddress;
  private final ImmutableList<InternetAddress> spec11BccEmailAddresses;
  private final InternetAddress alertRecipientAddress;
  private final ImmutableList<String> spec11WebResources;
  private final String registryName;

  @Inject
  Spec11EmailUtils(
      GmailClient gmailClient,
      Sleeper sleeper,
      TemplateRenderer templateRenderer,
      @Config("emailThrottleDuration") Duration emailThrottleDuration,
      @Config("newAlertRecipientEmailAddress") InternetAddress alertRecipientAddress,
      @Config("spec11OutgoingEmailAddress") InternetAddress spec11OutgoingEmailAddress,
      @Config("spec11BccEmailAddresses") ImmutableList<InternetAddress> spec11BccEmailAddresses,
      @Config("spec11WebResources") ImmutableList<String> spec11WebResources,
      @Config("registryName") String registryName) {
    this.gmailClient = gmailClient;
    this.sleeper = sleeper;
    this.templateRenderer = templateRenderer;
    this.emailThrottleDuration = emailThrottleDuration;
    this.outgoingEmailAddress = spec11OutgoingEmailAddress;
    this.spec11BccEmailAddresses = spec11BccEmailAddresses;
    this.alertRecipientAddress = alertRecipientAddress;
    this.spec11WebResources = spec11WebResources;
    this.registryName = registryName;
  }

  /**
   * Processes a list of registrar/list-of-threat pairings and sends notification emails to the
   * appropriate addresses.
   *
   * @param date the date the report was generated
   * @param template the email template to use
   * @param subject the subject line for the emails
   * @param registrarThreatMatchesSet a set of {@link RegistrarThreatMatches} to be emailed
   * @throws RuntimeException if emailing fails for one or more registrars
   */
  void emailSpec11Reports(
      LocalDate date,
      Spec11EmailTemplate template,
      String subject,
      ImmutableSet<RegistrarThreatMatches> registrarThreatMatchesSet) {
    ImmutableMap.Builder<RegistrarThreatMatches, Throwable> failedMatchesBuilder =
        ImmutableMap.builder();
    int numRegistrarsEmailed = 0;
    for (RegistrarThreatMatches registrarThreatMatches : registrarThreatMatchesSet) {
      RegistrarThreatMatches filteredMatches = filterOutNonPublishedMatches(registrarThreatMatches);
      if (!filteredMatches.threatMatches().isEmpty()) {
        if (numRegistrarsEmailed > 0) {
          sleeper.sleepInterruptibly(emailThrottleDuration);
        }
        try {
          // Handle exceptions individually per registrar so that one failed email doesn't prevent
          // the rest from being sent.
          emailRegistrar(date, template, subject, filteredMatches);
          numRegistrarsEmailed++;
        } catch (Throwable e) {
          failedMatchesBuilder.put(registrarThreatMatches, getRootCause(e));
        }
      }
    }
    logger.atInfo().log("Emailed Spec11 reports to %s registrars.", numRegistrarsEmailed);

    ImmutableMap<RegistrarThreatMatches, Throwable> failedMatches = failedMatchesBuilder.build();
    if (!failedMatches.isEmpty()) {
      ImmutableList<Map.Entry<RegistrarThreatMatches, Throwable>> failedMatchesList =
          failedMatches.entrySet().asList();
      // Send an alert email and throw a RuntimeException with the first failure as the cause,
      // but log the rest so that we have that information.
      Throwable firstThrowable = failedMatchesList.get(0).getValue();
      sendAlertEmail(
          String.format("Spec11 Emailing Failure %s", date),
          String.format("Emailing Spec11 reports failed due to %s", firstThrowable.getMessage()));
      for (int i = 1; i < failedMatches.size(); i++) {
        logger.atSevere().withCause(failedMatchesList.get(i).getValue()).log(
            "Additional exception thrown when sending email to registrar %s, in addition to the"
                + " re-thrown exception.",
            failedMatchesList.get(i).getKey().registrarId());
      }
      throw new RuntimeException(
          "Emailing Spec11 reports failed, first exception:", firstThrowable);
    }
    sendAlertEmail(
        String.format("Spec11 Pipeline Success %s", date),
        "Spec11 reporting completed successfully.");
  }

  private RegistrarThreatMatches filterOutNonPublishedMatches(
      RegistrarThreatMatches registrarThreatMatches) {
    ImmutableList<ThreatMatch> filteredMatches =
        tm().transact(
                () ->
                    registrarThreatMatches.threatMatches().stream()
                        .filter(
                            threatMatch ->
                                tm()
                                    .createQueryComposer(Domain.class)
                                    .where("domainName", Comparator.EQ, threatMatch.domainName())
                                    .stream()
                                    .anyMatch(Domain::shouldPublishToDns))
                        .collect(toImmutableList()));
    return RegistrarThreatMatches.create(registrarThreatMatches.registrarId(), filteredMatches);
  }

  private void emailRegistrar(
      LocalDate date,
      Spec11EmailTemplate template,
      String subject,
      RegistrarThreatMatches registrarThreatMatches)
      throws MessagingException {
    gmailClient.sendEmail(
        EmailMessage.newBuilder()
            .setSubject(subject)
            .setBody(getEmailBody(date, template, registrarThreatMatches))
            .setContentType(MediaType.HTML_UTF_8)
            .addRecipient(getEmailAddressForRegistrar(registrarThreatMatches.registrarId()))
            .setBccs(spec11BccEmailAddresses)
            .build());
  }

  /**
   * Renders the email body using the specified template and registrar threat matches.
   *
   * @param date the date the report was generated
   * @param template the email template to use
   * @param registrarThreatMatches the matches for a specific registrar
   * @return the rendered email body as an HTML string
   */
  private String getEmailBody(
      LocalDate date, Spec11EmailTemplate template, RegistrarThreatMatches registrarThreatMatches) {
    // FreeMarker templates require that data be in raw map/list form or bean-style POJOs.
    // We convert the ThreatMatch records to maps here to ensure compatibility and to
    // apply email-safe domain name transformations.
    ImmutableList<ImmutableMap<String, String>> threatMatchMap =
        registrarThreatMatches.threatMatches().stream()
            .map(
                threatMatch ->
                    ImmutableMap.of(
                        "domainName", toEmailSafeString(threatMatch.domainName()),
                        "threatType", threatMatch.threatType()))
            .collect(toImmutableList());

    ImmutableMap<String, Object> data =
        ImmutableMap.of(
            "date", date.toString(),
            "registry", registryName,
            "replyToEmail", outgoingEmailAddress.getAddress(),
            "threats", threatMatchMap,
            "resources", spec11WebResources);
    return templateRenderer.render(template.getFtlPath(), data);
  }

  // Mutates a known bad domain to pass spam checks by Email sender and clients, as suggested by
  // the Gmail abuse-detection team.
  private String toEmailSafeString(String knownUnsafeDomain) {
    return knownUnsafeDomain.replace(".", "[.]");
  }

  /** Sends an e-mail indicating the state of the spec11 pipeline, with a given subject and body. */
  void sendAlertEmail(String subject, String body) {
    try {
      gmailClient.sendEmail(
          EmailMessage.newBuilder()
              .addRecipient(alertRecipientAddress)
              .setBody(body)
              .setSubject(subject)
              .build());
    } catch (Throwable e) {
      throw new RuntimeException("The spec11 alert e-mail system failed.", e);
    }
  }

  private InternetAddress getEmailAddressForRegistrar(String registrarId)
      throws MessagingException {
    // Attempt to use the registrar's RDAP abuse contact, then fall back to the regular address.
    Registrar registrar =
        Registrar.loadByRegistrarIdCached(registrarId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Could not find registrar %s", registrarId)));
    return new InternetAddress(
        registrar
            .getRdapAbuseContact()
            .map(RegistrarPoc::getEmailAddress)
            .orElse(registrar.getEmailAddress()));
  }
}
