// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.batch.SendExpiringCertificateNotificationEmailAction.CertificateType;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.util.SelfSignedCaCertificate;
import google.registry.util.SendEmailService;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

class SendExpiringCertificateNotificationEmailActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();
  private final FakeClock clock = new FakeClock(DateTime.parse("2021-05-24T20:21:22Z"));
  private CertificateChecker certificateChecker;
  private SendExpiringCertificateNotificationEmailAction action;
  private Registrar registrar;
  @Mock private SendEmailService sendEmailService;

  @BeforeEach
  void beforeEach() throws AddressException {
    certificateChecker =
        new CertificateChecker(
            ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
            30,
            15,
            2048,
            ImmutableSet.of("secp256r1", "secp384r1"),
            clock);
    InternetAddress address = new InternetAddress("test@example.com");
    String expirationWarningEmailBodyText =
        " Hello Registrar %s,\n" + "       The %s certificate is expiring on %s.";
    String expirationWarningEmailSubjectText = "expiring certificate notification email";
    action =
        new SendExpiringCertificateNotificationEmailAction(
            address,
            expirationWarningEmailBodyText,
            expirationWarningEmailSubjectText,
            sendEmailService,
            certificateChecker);

    registrar =
        new Registrar.Builder()
            .setClientId("cliient")
            .setRegistrarName("theregistrar")
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.ACTIVE)
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 45th street"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("11101")
                    .setCountryCode("US")
                    .build())
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 45th street"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("11101")
                    .setCountryCode("US")
                    .build())
            .setEmailAddress("contact-us@test.example")
            .setWhoisServer("whois.registrar.example")
            .setUrl("http://www.test.example")
            .build();
  }

  /** Returns a sample registrar with a customized registrar name, client id and certificate* */
  private Registrar createRegistrar(
      String clientId,
      String registrarName,
      @Nullable X509Certificate certificate,
      @Nullable X509Certificate failOverCertificate)
      throws Exception {
    // set up registrar with sample data
    Registrar.Builder builder =
        new Registrar.Builder()
            .setClientId(clientId)
            .setRegistrarName(registrarName)
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.ACTIVE)
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 45th street"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("11101")
                    .setCountryCode("US")
                    .build())
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 45th street"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("11101")
                    .setCountryCode("US")
                    .build())
            .setPhoneNumber("+1.123456789")
            .setFaxNumber("+1.123456789")
            .setEmailAddress("contact-us@test.example")
            .setWhoisServer("whois.registrar.example")
            .setUrl("http://www.test.example");

    if (failOverCertificate != null) {
      builder.setFailoverClientCertificate(
          certificateChecker.serializeCertificate(failOverCertificate), clock.nowUtc());
    }
    if (certificate != null) {
      builder.setClientCertificate(
          certificateChecker.serializeCertificate(certificate), clock.nowUtc());
    }
    return builder.build();
  }

  @Test
  void sendNotificationEmail_returnsCurrentDateTime() {}

  @Test
  void sendNotificationEmail_returnsStartOfTime_noEmailRecipients() {}

  @Test
  void sendNotificationEmail_returnsStartOfTime_sendEmailServiceException() {}

  @Test
  void sendNotificationEmail_returnsStartOfTime_noCertificate() {
    DateTime expectedOutput = START_OF_TIME;
    DateTime lastExpiringCertNotificationSentDate = START_OF_TIME;
    CertificateType certificateType = CertificateType.FAILOVER;
    Optional<String> certificate = Optional.empty();
    assertThat(
            action.sendNotificationEmail(
                registrar, lastExpiringCertNotificationSentDate, certificateType, certificate))
        .isEqualTo(expectedOutput);
  }

  @Test
  void sendNotificationEmails_allEmailsBeingAttemptedToSend() {}

  @Test
  void sendNotificationEmails_allEmailsBeingAttemptedToSend_onlyMainCertificates() {}

  @Test
  void sendNotificationEmails_allEmailsBeingAttemptedToSend_onlyFailOverCertificates() {}

  @Test
  void sendNotificationEmails_allEmailsBeingAttemptedToSend_mixedOfCertificates() {}

  @Test
  void updateLastNotificationSentDate_updatedSuccessfully() {}

  @Test
  void updateLastNotificationSentDate_noUpdates_noLastNotificationSentDate() {}

  @Test
  void updateLastNotificationSentDate_noUpdates_tmTransactionError() {}

  @Test
  void getRegistrarsWithExpiringCertificates_returnsPartOfRegistrars() throws Exception {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    int numOfRegistrarsWithExpiringCertificates = 2;
    for (int i = 1; i <= numOfRegistrarsWithExpiringCertificates; i++) {
      Registrar reg = createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null);
      persistResource(reg);
    }
    for (int i = numOfRegistrarsWithExpiringCertificates; i <= numOfRegistrars; i++) {
      Registrar reg = createRegistrar("goodcert" + i, "name" + i, certificate, null);
      persistResource(reg);
    }

    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsPartOfRegistrars_failOverCertificateBranch()
      throws Exception {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    int numOfRegistrarsWithExpiringCertificates = 2;
    for (int i = 1; i <= numOfRegistrarsWithExpiringCertificates; i++) {
      Registrar reg = createRegistrar("oldcert" + i, "name" + i, null, expiringCertificate);
      persistResource(reg);
    }
    for (int i = numOfRegistrarsWithExpiringCertificates; i <= numOfRegistrars; i++) {
      Registrar reg = createRegistrar("goodcert" + i, "name" + i, null, certificate);
      persistResource(reg);
    }

    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsAllRegistrars() throws Exception {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();

    int numOfRegistrarsWithExpiringCertificates = 5;
    for (int i = 1; i <= numOfRegistrarsWithExpiringCertificates; i++) {
      Registrar reg = createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null);
      persistResource(reg);
    }
    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsNoRegistrars() throws Exception {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    for (int i = 1; i <= numOfRegistrars; i++) {
      Registrar reg = createRegistrar("goodcert" + i, "name" + i, certificate, null);
      persistResource(reg);
    }
    int numOfRegistrarsWithExpiringCertificates = 0;

    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_noRegistrarsInDatabase() {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);

    ImmutableList<Registrar> results = action.getRegistrarsWithExpiringCertificates();
    int numOfRegistrars = 0;
    assertThat(results.size()).isEqualTo(numOfRegistrars);
  }

  @Test
  void sendNotification_failure() {}

  @Test
  void getEmailAddresses_success_returnsAnEmptyList() {
    // remove registrars being created by default via AppEngineExtension
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
    ImmutableSet<InternetAddress> recipients = action.getEmailAddresses(registrar, Type.TECH);
    assertThat(recipients).isEmpty();
  }

  @Test
  void getEmailAddresses_success_returnsAListOfEmails() throws AddressException {
    ImmutableList<RegistrarContact> contacts =
        ImmutableList.of(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("John Doe")
                .setEmailAddress("jd@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("John Smith")
                .setEmailAddress("js@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Will Doe")
                .setEmailAddress("will@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Mike Doe")
                .setEmailAddress("mike@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .build(),
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("John T")
                .setEmailAddress("john@example-registrar.tld")
                .setPhoneNumber("+1.3105551215")
                .setFaxNumber("+1.3105551216")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.ADMIN))
                .setVisibleInWhoisAsTech(true)
                .build());
    persistSimpleResources(contacts);
    persistResource(registrar);
    assertThat(action.getEmailAddresses(registrar, Type.TECH))
        .containsExactly(
            new InternetAddress("will@example-registrar.tld"),
            new InternetAddress("jd@example-registrar.tld"),
            new InternetAddress("js@example-registrar.tld"));
    assertThat(action.getEmailAddresses(registrar, Type.ADMIN))
        .containsExactly(
            new InternetAddress("mike@example-registrar.tld"),
            new InternetAddress("john@example-registrar.tld"));
  }

  @Test
  void getEmailAddresses_failure_returnsPartialListOfEmails_skipInvalidEmails() {
    // when building a new RegistrarContact object, there's already an email validation process.
    // if the registrarContact is created successful, the email address of the contact object
    // should already be validated. Ideally, there should not be an AddressException when creating
    // a new InternetAddress using the email address string of the contact object.
  }

  @Test
  void getEmailBody_returnsTexForPrimary() {
    String registrarName = "good registrar";
    String certExpirationDateStr = "2021-06-15";
    CertificateType certificateType = CertificateType.PRIMARY;
    String emailBody =
        action.getEmailBody(
            registrarName, certificateType, DateTime.parse(certExpirationDateStr).toDate());
    assertThat(emailBody).contains(registrarName);
    assertThat(emailBody).contains(certificateType.getDisplayName());
    assertThat(emailBody).contains(certExpirationDateStr);
  }

  @Test
  void getEmailBody_returnsTexForFailOver() {
    String registrarName = "good registrar";
    String certExpirationDateStr = "2021-06-15";
    CertificateType certificateType = CertificateType.FAILOVER;
    String emailBody =
        action.getEmailBody(
            registrarName, certificateType, DateTime.parse(certExpirationDateStr).toDate());
    assertThat(emailBody).contains(registrarName);
    assertThat(emailBody).contains(certificateType.getDisplayName());
    assertThat(emailBody).contains(certExpirationDateStr);
  }

  @Test
  void getEmailBody_throwsNullPointerException_noExpirationDate() {
    assertThrows(
        NullPointerException.class,
        () -> action.getEmailBody("good registrar", CertificateType.FAILOVER, null));
  }

  @Test
  void getEmailBody_throwsNullPointerException_noCertificateType() {
    assertThrows(
        NullPointerException.class,
        () -> action.getEmailBody("good registrar", null, DateTime.parse("2021-06-15").toDate()));
  }
}
