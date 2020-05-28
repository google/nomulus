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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.CLIENT_HOLD;
import static google.registry.model.eppcommon.StatusValue.SERVER_HOLD;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParserTest.getMatchA;
import static google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParserTest.getMatchB;
import static google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParserTest.sampleThreatMatches;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.MediaType;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.reporting.spec11.soy.Spec11EmailSoyInfo;
import google.registry.testing.AppEngineRule;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import org.joda.time.LocalDate;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link Spec11EmailUtils}. */
@RunWith(JUnit4.class)
public class Spec11EmailUtilsTest {

  private static final ImmutableList<String> FAKE_RESOURCES = ImmutableList.of("foo");
  private static final String DAILY_EMAIL_FORMAT =
      "Dear registrar partner,<p>Super Cool Registry conducts a daily analysis of all domains"
          + " registered in its TLDs to identify potential security concerns. On 2018-07-15, the"
          + " following domains that your registrar manages were flagged for potential security"
          + " concerns:</p><table><tr><th>Domain Name</th><th>Threat Type</th></tr>%s"
          + "</table><p><b>Please communicate these findings to the registrant and work with the"
          + " registrant to mitigate any security issues and have the domains delisted.</b></p>"
          + "Some helpful resources for getting off a blocked list include:"
          + "<ul><li>foo</li></ul><p>If you believe that any of the domains were reported in"
          + " error, or are still receiving reports for issues that have been remediated, please"
          + " <a href=\"https://safebrowsing.google.com/safebrowsing/report_error/?hl=en\">submit"
          + " a request</a> to have the site reviewed.</p><p>You will continue to receive daily"
          + " notices when new domains managed by your registrar are flagged for abuse, as well as"
          + " a monthly summary of all of your domains under management that remain flagged for"
          + " abuse.</p><p>If you would like to change the email to which these notices are sent"
          + " please update your abuse contact using your registrar portal account.</p><p>If you"
          + " have any questions regarding this notice, please contact abuse@test.com.</p>";
  private static final String MONTHLY_EMAIL_FORMAT =
      "Dear registrar partner,<p>Super Cool Registry previously notified you when the following"
          + " domains managed by your registrar were flagged for potential security"
          + " concerns.</p><p>The following domains that you manage continue to be flagged by our"
          + " analysis for potential security concerns. This may be because the registrants have"
          + " not completed the requisite steps to mitigate the potential security abuse and/or"
          + " have it reviewed and delisted.</p><table><tr><th>Domain Name</th><th>Threat"
          + " Type</th></tr>%s</table><p>Please work with the registrant to mitigate any security"
          + " issues and have the domains delisted. If you believe that any of the domains were"
          + " reported in error, or are still receiving reports for issues that have been"
          + " remediated, please <a"
          + " href=\"https://safebrowsing.google.com/safebrowsing/report_error/?hl=en\">submit a"
          + " request</a> to have the site reviewed.</p>Some helpful resources for getting off a"
          + " blocked list include:<ul><li>foo</li></ul><p>You will continue to receive a monthly"
          + " summary of all domains managed by your registrar that remain on our lists of"
          + " potential security threats. You will also receive a daily notice when any new"
          + " domains are added to these lists.</p><p>If you have any questions regarding this"
          + " notice, please contact abuse@test.com.</p>";

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private SendEmailService emailService;
  private Spec11EmailUtils emailUtils;
  private Spec11RegistrarThreatMatchesParser parser;
  private ArgumentCaptor<EmailMessage> contentCaptor;
  private final LocalDate date = new LocalDate(2018, 7, 15);

  private DomainBase aDomain;
  private DomainBase bDomain;

  @Before
  public void setUp() throws Exception {
    emailService = mock(SendEmailService.class);
    parser = mock(Spec11RegistrarThreatMatchesParser.class);
    when(parser.getRegistrarThreatMatches(date)).thenReturn(sampleThreatMatches());
    contentCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    emailUtils =
        new Spec11EmailUtils(
            emailService,
            new InternetAddress("my-receiver@test.com"),
            new InternetAddress("abuse@test.com"),
            ImmutableList.of(
                new InternetAddress("abuse@test.com"), new InternetAddress("bcc@test.com")),
            FAKE_RESOURCES,
            "Super Cool Registry");

    createTld("com");
    HostResource host = persistActiveHost("ns1.example.com");
    aDomain = persistDomainWithHost("a.com", host);
    bDomain = persistDomainWithHost("b.com", host);
    persistDomainWithHost("c.com", host);
  }

  @Test
  public void testSuccess_emailMonthlySpec11Reports() throws Exception {
    emailUtils.emailSpec11Reports(
        date,
        Spec11EmailSoyInfo.MONTHLY_SPEC_11_EMAIL,
        "Super Cool Registry Monthly Threat Detector [2018-07-15]",
        sampleThreatMatches());
    // We inspect individual parameters because Message doesn't implement equals().
    verify(emailService, times(3)).sendEmail(contentCaptor.capture());
    List<EmailMessage> capturedContents = contentCaptor.getAllValues();
    validateMessage(
        capturedContents.get(0),
        "abuse@test.com",
        "the.registrar@example.com",
        ImmutableList.of("abuse@test.com", "bcc@test.com"),
        "Super Cool Registry Monthly Threat Detector [2018-07-15]",
        String.format(MONTHLY_EMAIL_FORMAT, "<tr><td>a.com</td><td>MALWARE</td></tr>"),
        Optional.of(MediaType.HTML_UTF_8));
    validateMessage(
        capturedContents.get(1),
        "abuse@test.com",
        "new.registrar@example.com",
        ImmutableList.of("abuse@test.com", "bcc@test.com"),
        "Super Cool Registry Monthly Threat Detector [2018-07-15]",
        String.format(
            MONTHLY_EMAIL_FORMAT,
            "<tr><td>b.com</td><td>MALWARE</td></tr><tr><td>c.com</td><td>MALWARE</td></tr>"),
        Optional.of(MediaType.HTML_UTF_8));
    validateMessage(
        capturedContents.get(2),
        "abuse@test.com",
        "my-receiver@test.com",
        ImmutableList.of(),
        "Spec11 Pipeline Success 2018-07-15",
        "Spec11 reporting completed successfully.",
        Optional.empty());
  }

  @Test
  public void testSuccess_emailDailySpec11Reports() throws Exception {
    emailUtils.emailSpec11Reports(
        date,
        Spec11EmailSoyInfo.DAILY_SPEC_11_EMAIL,
        "Super Cool Registry Daily Threat Detector [2018-07-15]",
        sampleThreatMatches());
    // We inspect individual parameters because Message doesn't implement equals().
    verify(emailService, times(3)).sendEmail(contentCaptor.capture());
    List<EmailMessage> capturedMessages = contentCaptor.getAllValues();
    validateMessage(
        capturedMessages.get(0),
        "abuse@test.com",
        "the.registrar@example.com",
        ImmutableList.of("abuse@test.com", "bcc@test.com"),
        "Super Cool Registry Daily Threat Detector [2018-07-15]",
        String.format(DAILY_EMAIL_FORMAT, "<tr><td>a.com</td><td>MALWARE</td></tr>"),
        Optional.of(MediaType.HTML_UTF_8));
    validateMessage(
        capturedMessages.get(1),
        "abuse@test.com",
        "new.registrar@example.com",
        ImmutableList.of("abuse@test.com", "bcc@test.com"),
        "Super Cool Registry Daily Threat Detector [2018-07-15]",
        String.format(
            DAILY_EMAIL_FORMAT,
            "<tr><td>b.com</td><td>MALWARE</td></tr><tr><td>c.com</td><td>MALWARE</td></tr>"),
        Optional.of(MediaType.HTML_UTF_8));
    validateMessage(
        capturedMessages.get(2),
        "abuse@test.com",
        "my-receiver@test.com",
        ImmutableList.of(),
        "Spec11 Pipeline Success 2018-07-15",
        "Spec11 reporting completed successfully.",
        Optional.empty());
  }

  @Test
  public void testSuccess_skipsInactiveDomain() throws Exception {
    // CLIENT_HOLD and SERVER_HOLD mean no DNS so we don't need to email it out
    persistResource(
        ofy().load().entity(aDomain).now().asBuilder().addStatusValue(SERVER_HOLD)
            .build());
    persistResource(
        ofy().load().entity(bDomain).now().asBuilder().addStatusValue(CLIENT_HOLD)
            .build());
    emailUtils.emailSpec11Reports(
        date,
        Spec11EmailSoyInfo.MONTHLY_SPEC_11_EMAIL,
        "Super Cool Registry Monthly Threat Detector [2018-07-15]",
        sampleThreatMatches());
    // We inspect individual parameters because Message doesn't implement equals().
    verify(emailService, times(2)).sendEmail(contentCaptor.capture());
    List<EmailMessage> capturedContents = contentCaptor.getAllValues();
    validateMessage(
        capturedContents.get(0),
        "abuse@test.com",
        "new.registrar@example.com",
        ImmutableList.of("abuse@test.com", "bcc@test.com"),
        "Super Cool Registry Monthly Threat Detector [2018-07-15]",
        String.format(MONTHLY_EMAIL_FORMAT, "<tr><td>c.com</td><td>MALWARE</td></tr>"),
        Optional.of(MediaType.HTML_UTF_8));
    validateMessage(
        capturedContents.get(1),
        "abuse@test.com",
        "my-receiver@test.com",
        ImmutableList.of(),
        "Spec11 Pipeline Success 2018-07-15",
        "Spec11 reporting completed successfully.",
        Optional.empty());
  }

  @Test
  public void testOneFailure_sendsAlert() throws Exception {
    // If there is one failure, we should still send the other message and then an alert email
    LinkedHashSet<RegistrarThreatMatches> matches = new LinkedHashSet<>();
    matches.add(getMatchA());
    matches.add(getMatchB());
    doThrow(new RuntimeException(new MessagingException("expected")))
        .doNothing()
        .doNothing()
        .when(emailService)
        .sendEmail(contentCaptor.capture());
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                emailUtils.emailSpec11Reports(
                    date,
                    Spec11EmailSoyInfo.MONTHLY_SPEC_11_EMAIL,
                    "Super Cool Registry Monthly Threat Detector [2018-07-15]",
                    ImmutableSet.copyOf(matches)));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Emailing Spec11 reports failed, first exception:");
    assertThat(thrown).hasCauseThat().hasMessageThat().isEqualTo("expected");
    // Verify we sent an e-mail alert
    verify(emailService, times(3)).sendEmail(contentCaptor.capture());
    List<EmailMessage> capturedMessages = contentCaptor.getAllValues();
    validateMessage(
        capturedMessages.get(0),
        "abuse@test.com",
        "the.registrar@example.com",
        ImmutableList.of("abuse@test.com", "bcc@test.com"),
        "Super Cool Registry Monthly Threat Detector [2018-07-15]",
        String.format(MONTHLY_EMAIL_FORMAT, "<tr><td>a.com</td><td>MALWARE</td></tr>"),
        Optional.of(MediaType.HTML_UTF_8));
    validateMessage(
        capturedMessages.get(1),
        "abuse@test.com",
        "new.registrar@example.com",
        ImmutableList.of("abuse@test.com", "bcc@test.com"),
        "Super Cool Registry Monthly Threat Detector [2018-07-15]",
        String.format(
            MONTHLY_EMAIL_FORMAT,
            "<tr><td>b.com</td><td>MALWARE</td></tr><tr><td>c.com</td><td>MALWARE</td></tr>"),
        Optional.of(MediaType.HTML_UTF_8));
    validateMessage(
        capturedMessages.get(2),
        "abuse@test.com",
        "my-receiver@test.com",
        ImmutableList.of(),
        "Spec11 Emailing Failure 2018-07-15",
        "Emailing Spec11 reports failed due to expected",
        Optional.empty());
  }

  @Test
  public void testSuccess_sendAlertEmail() throws Exception {
    emailUtils.sendAlertEmail("Spec11 Pipeline Alert: 2018-07", "Alert!");
    verify(emailService).sendEmail(contentCaptor.capture());
    validateMessage(
        contentCaptor.getValue(),
        "abuse@test.com",
        "my-receiver@test.com",
        ImmutableList.of(),
        "Spec11 Pipeline Alert: 2018-07",
        "Alert!",
        Optional.empty());
  }

  @Test
  public void testSuccess_useWhoisAbuseEmailIfAvailable() throws Exception {
    // if John Doe is the whois abuse contact, email them instead of the regular email
    persistResource(
        AppEngineRule.makeRegistrarContact2()
            .asBuilder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setVisibleInDomainWhoisAsAbuse(true)
            .build());
    emailUtils.emailSpec11Reports(
        date,
        Spec11EmailSoyInfo.MONTHLY_SPEC_11_EMAIL,
        "Super Cool Registry Monthly Threat Detector [2018-07-15]",
        sampleThreatMatches());
    verify(emailService, times(3)).sendEmail(contentCaptor.capture());
    assertThat(contentCaptor.getAllValues().get(0).recipients())
        .containsExactly(new InternetAddress("johndoe@theregistrar.com"));
  }

  @Test
  public void testFailure_badClientId() {
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                emailUtils.emailSpec11Reports(
                    date,
                    Spec11EmailSoyInfo.MONTHLY_SPEC_11_EMAIL,
                    "Super Cool Registry Monthly Threat Detector [2018-07-15]",
                    ImmutableSet.of(
                        RegistrarThreatMatches.create(
                            "badClientId", getMatchA().threatMatches()))));
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("Could not find registrar badClientId");
    assertThat(thrown).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
  }

  private void validateMessage(
      EmailMessage message,
      String from,
      String recipient,
      ImmutableList<String> bccs,
      String subject,
      String body,
      Optional<MediaType> contentType)
      throws MessagingException {
    EmailMessage.Builder expectedContentBuilder =
        EmailMessage.newBuilder()
            .setFrom(new InternetAddress(from))
            .addRecipient(new InternetAddress(recipient))
            .setSubject(subject)
            .setBody(body);

    for (String bcc : bccs) {
      expectedContentBuilder.addBcc(new InternetAddress(bcc));
    }
    contentType.ifPresent(expectedContentBuilder::setContentType);
    assertThat(message).isEqualTo(expectedContentBuilder.build());
  }

  private static DomainBase persistDomainWithHost(String domainName, HostResource host) {
    return persistResource(
        newDomainBase(domainName)
            .asBuilder()
            .setNameservers(ImmutableSet.of(host.createVKey()))
            .build());
  }
}
