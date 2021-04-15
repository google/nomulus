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
import static com.google.common.io.Resources.getResource;
import static google.registry.persistence.transaction.QueryComposer.Comparator;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.template.soy.SoyFileSet;
import com.google.template.soy.parseinfo.SoyTemplateInfo;
import com.google.template.soy.tofu.SoyTofu;
import com.google.template.soy.tofu.SoyTofu.Renderer;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.DomainBase;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.reporting.spec11.soy.Spec11EmailSoyInfo;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import org.joda.time.LocalDate;

/** Provides e-mail functionality for Spec11 tasks, such as sending Spec11 reports to registrars. */
public class Spec11EmailUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final SoyTofu SOY_SAUCE =
      SoyFileSet.builder()
          .add(
              getResource(
                  Spec11EmailSoyInfo.getInstance().getClass(),
                  Spec11EmailSoyInfo.getInstance().getFileName()))
          .build()
          .compileToTofu();

  private final SendEmailService emailService;
  private final InternetAddress outgoingEmailAddress;
  private final ImmutableList<InternetAddress> spec11BccEmailAddresses;
  private final InternetAddress alertRecipientAddress;
  private final ImmutableList<String> spec11WebResources;
  private final String registryName;

  @Inject
  Spec11EmailUtils(
      SendEmailService emailService,
      @Config("alertRecipientEmailAddress") InternetAddress alertRecipientAddress,
      @Config("spec11OutgoingEmailAddress") InternetAddress spec11OutgoingEmailAddress,
      @Config("spec11BccEmailAddresses") ImmutableList<InternetAddress> spec11BccEmailAddresses,
      @Config("spec11WebResources") ImmutableList<String> spec11WebResources,
      @Config("registryName") String registryName) {
    this.emailService = emailService;
    this.outgoingEmailAddress = spec11OutgoingEmailAddress;
    this.spec11BccEmailAddresses = spec11BccEmailAddresses;
    this.alertRecipientAddress = alertRecipientAddress;
    this.spec11WebResources = spec11WebResources;
    this.registryName = registryName;
  }

  /**
   * Processes a list of registrar/list-of-threat pairings and sends a notification email to the
   * appropriate address.
   */
  void emailSpec11Reports(
      LocalDate date,
      SoyTemplateInfo soyTemplateInfo,
      String subject,
      ImmutableSet<RegistrarThreatMatches> registrarThreatMatchesSet) {
    ImmutableMap.Builder<RegistrarThreatMatches, Throwable> failedMatchesBuilder =
        ImmutableMap.builder();
    for (RegistrarThreatMatches registrarThreatMatches : registrarThreatMatchesSet) {
      RegistrarThreatMatches filteredMatches = filterOutNonPublishedMatches(registrarThreatMatches);
      if (!filteredMatches.threatMatches().isEmpty()) {
        try {
          // Handle exceptions individually per registrar so that one failed email doesn't prevent
          // the rest from being sent.
          emailRegistrar(date, soyTemplateInfo, subject, filteredMatches);
        } catch (Throwable e) {
          failedMatchesBuilder.put(registrarThreatMatches, getRootCause(e));
        }
      }
    }
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
                + " re-thrown exception",
            failedMatchesList.get(i).getKey().clientId());
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
        transactIfJpaTm(
            () -> {
              return registrarThreatMatches.threatMatches().stream()
                  .filter(
                      threatMatch ->
                          tm().createQueryComposer(DomainBase.class)
                              .where(
                                  "fullyQualifiedDomainName",
                                  Comparator.EQ,
                                  threatMatch.fullyQualifiedDomainName())
                              .getSingleResult()
                              .shouldPublishToDns())
                  .collect(toImmutableList());
            });
    return RegistrarThreatMatches.create(registrarThreatMatches.clientId(), filteredMatches);
  }

  private void emailRegistrar(
      LocalDate date,
      SoyTemplateInfo soyTemplateInfo,
      String subject,
      RegistrarThreatMatches registrarThreatMatches)
      throws MessagingException {
    emailService.sendEmail(
        EmailMessage.newBuilder()
            .setSubject(subject)
            .setBody(getContent(date, soyTemplateInfo, registrarThreatMatches))
            .setContentType(MediaType.HTML_UTF_8)
            .setFrom(outgoingEmailAddress)
            .addRecipient(getEmailAddressForRegistrar(registrarThreatMatches.clientId()))
            .setBccs(spec11BccEmailAddresses)
            .build());
  }

  private String getContent(
      LocalDate date,
      SoyTemplateInfo soyTemplateInfo,
      RegistrarThreatMatches registrarThreatMatches) {
    Renderer renderer = SOY_SAUCE.newRenderer(soyTemplateInfo);
    // Soy templates require that data be in raw map/list form.
    List<Map<String, String>> threatMatchMap =
        registrarThreatMatches.threatMatches().stream()
            .map(
                threatMatch ->
                    ImmutableMap.of(
                        "fullyQualifiedDomainName", threatMatch.fullyQualifiedDomainName(),
                        "threatType", threatMatch.threatType()))
            .collect(toImmutableList());

    Map<String, Object> data =
        ImmutableMap.of(
            "date", date.toString(),
            "registry", registryName,
            "replyToEmail", outgoingEmailAddress.getAddress(),
            "threats", threatMatchMap,
            "resources", spec11WebResources);
    renderer.setData(data);
    return renderer.render();
  }

  /** Sends an e-mail indicating the state of the spec11 pipeline, with a given subject and body. */
  void sendAlertEmail(String subject, String body) {
    try {
      emailService.sendEmail(
          EmailMessage.newBuilder()
              .setFrom(outgoingEmailAddress)
              .addRecipient(alertRecipientAddress)
              .setBody(body)
              .setSubject(subject)
              .build());
    } catch (Throwable e) {
      throw new RuntimeException("The spec11 alert e-mail system failed.", e);
    }
  }

  private InternetAddress getEmailAddressForRegistrar(String clientId) throws MessagingException {
    // Attempt to use the registrar's WHOIS abuse contact, then fall back to the regular address.
    Registrar registrar =
        Registrar.loadByClientIdCached(clientId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Could not find registrar %s", clientId)));
    return new InternetAddress(
        registrar
            .getWhoisAbuseContact()
            .map(RegistrarContact::getEmailAddress)
            .orElse(registrar.getEmailAddress()));
  }
}
