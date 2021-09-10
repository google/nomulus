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
import static google.registry.testing.AppEngineExtension.makeRegistrar1;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.batch.SendExpiringCertificateNotificationEmailAction.CertificateType;
import google.registry.batch.SendExpiringCertificateNotificationEmailAction.RegistrarInfo;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.request.Response;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import google.registry.util.SelfSignedCaCertificate;
import google.registry.util.SendEmailService;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link SendExpiringCertificateNotificationEmailAction}. */
@DualDatabaseTest
class SendExpiringCertificateNotificationEmailActionTest {

  private static final String EXPIRATION_WARNING_EMAIL_BODY_TEXT =
      " Dear %1$s,\n"
          + "\n"
          + "    We would like to inform you that your %2$s SSL certificate will expire at\n"
          + "    %3$s. Please take note that using expired certificates will prevent\n"
          + "    successful Registry login.\n"
          + "\n"
          + "    Kindly update your production account certificate within the support\n"
          + "    console using the following steps:\n"
          + "\n"
          + "      1. Navigate to support.registry.google and login using your\n"
          + "    %4$s@registry.google credentials.\n"
          + "          * If this is your first time logging in, you will be prompted to\n"
          + "          reset your password, so please keep your new password safe.\n"
          + "          * If you are already logged in with some other Google account(s) but\n"
          + "          not your %4$s@registry.google account, you need to click on\n"
          + "          “Add Account” and login using your %4$s@registry.google credentials.\n"
          + "      2. Select “Settings > Security” from the left navigation bar.\n"
          + "      3. Click “Edit” on the top left corner.\n"
          + "      4. Enter your full certificate string\n"
          + "         (including lines -----BEGIN CERTIFICATE----- and\n"
          + "         -----END CERTIFICATE-----) in the box.\n"
          + "      5. Click “Save”. If there are validation issues with the form, you will\n"
          + "         be prompted to fix them and click “Save” again.\n"
          + "\n"
          + "    A failover SSL certificate can also be added in order to prevent connection\n"
          + "    issues once your main certificate expires. Connecting with either of the\n"
          + "    certificates will work with our production EPP server.\n"
          + "\n"
          + "    Further information about our EPP connection requirements can be found in\n"
          + "    section 9.2 in the updated Technical Guide in your Google Drive folder.\n"
          + "\n"
          + "    Note that account certificate changes take a few minutes to become\n"
          + "    effective and that the existing connections will remain unaffected by\n"
          + "    the change.\n"
          + "\n"
          + "    If you also would like to update your OT&E account certificate, please send\n"
          + "    an email from your primary or technical contact to\n"
          + "    registry-support@google.com and include the full certificate string\n"
          + "    (including lines -----BEGIN CERTIFICATE----- and -----END CERTIFICATE-----).\n"
          + "\n"
          + "    Regards,\n"
          + "    Google Registry\n";

  private static final String EXPIRATION_WARNING_EMAIL_SUBJECT_TEXT =
      "[Important] Expiring SSL certificate for Google " + "Registry EPP connection";

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();
  private final FakeClock clock = new FakeClock(DateTime.parse("2021-05-24T20:21:22Z"));
  private final SendEmailService sendEmailService = mock(SendEmailService.class);
  private CertificateChecker certificateChecker;
  private SendExpiringCertificateNotificationEmailAction action;
  private Registrar sampleRegistrar;
  private Response response;

  @BeforeEach
  void beforeEach() throws Exception {
    certificateChecker =
        new CertificateChecker(
            ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
            30,
            15,
            2048,
            ImmutableSet.of("secp256r1", "secp384r1"),
            clock);

    action =
        new SendExpiringCertificateNotificationEmailAction(
            EXPIRATION_WARNING_EMAIL_BODY_TEXT,
            EXPIRATION_WARNING_EMAIL_SUBJECT_TEXT,
            new InternetAddress("test@example.com"),
            sendEmailService,
            certificateChecker,
            response);

    sampleRegistrar =
        persistResource(createRegistrar("clientId", "sampleRegistrar", null, null).build());
  }

  /** Returns a sample registrar with a customized registrar name, client id and certificate* */
  private Registrar.Builder createRegistrar(
      String registrarId,
      String registrarName,
      @Nullable X509Certificate certificate,
      @Nullable X509Certificate failOverCertificate)
      throws Exception {
    // set up only required fields sample test data
    Registrar.Builder builder =
        new Registrar.Builder()
            .setRegistrarId(registrarId)
            .setRegistrarName(registrarName)
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.ACTIVE)
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("very fake street"))
                    .setCity("city")
                    .setState("state")
                    .setZip("99999")
                    .setCountryCode("US")
                    .build())
            .setPhoneNumber("+0.000000000")
            .setFaxNumber("+9.999999999")
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
    return builder;
  }

  @TestOfyAndSql
  void sendNotificationEmail_returnsTrue() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Optional<String> cert =
        Optional.of(certificateChecker.serializeCertificate(expiringCertificate));
    Registrar registrar =
        persistResource(
            makeRegistrar1()
                .asBuilder()
                .setFailoverClientCertificate(cert.get(), clock.nowUtc())
                .build());
    ImmutableList<RegistrarContact> contacts =
        ImmutableList.of(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Will Doe")
                .setEmailAddress("will@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build());
    persistSimpleResources(contacts);
    persistResource(registrar);
    assertThat(
            action.sendNotificationEmail(registrar, START_OF_TIME, CertificateType.FAILOVER, cert))
        .isEqualTo(true);
  }

  @TestOfyAndSql
  void sendNotificationEmail_returnsFalse_noEmailRecipients() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-02T00:00:00Z"))
            .cert();
    Optional<String> cert =
        Optional.of(certificateChecker.serializeCertificate(expiringCertificate));
    assertThat(
            action.sendNotificationEmail(
                sampleRegistrar, START_OF_TIME, CertificateType.FAILOVER, cert))
        .isEqualTo(false);
  }

  @TestOfyAndSql
  void sendNotificationEmail_throwsRunTimeException() throws Exception {
    doThrow(new RuntimeException("this is a runtime exception"))
        .when(sendEmailService)
        .sendEmail(any());
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Optional<String> cert =
        Optional.of(certificateChecker.serializeCertificate(expiringCertificate));
    Registrar registrar =
        persistResource(
            makeRegistrar1()
                .asBuilder()
                .setFailoverClientCertificate(cert.get(), clock.nowUtc())
                .build());
    ImmutableList<RegistrarContact> contacts =
        ImmutableList.of(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setName("Will Doe")
                .setEmailAddress("will@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarContact.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build());
    persistSimpleResources(contacts);
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                action.sendNotificationEmail(
                    registrar, START_OF_TIME, CertificateType.FAILOVER, cert));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format(
                "Failed to send expiring certificate notification email to registrar %s",
                registrar.getRegistrarName()));
  }

  @TestOfyAndSql
  void sendNotificationEmail_returnsFalse_noCertificate() {
    assertThat(
            action.sendNotificationEmail(
                sampleRegistrar, START_OF_TIME, CertificateType.FAILOVER, Optional.empty()))
        .isEqualTo(false);
  }

  @TestOfyAndSql
  void sendNotificationEmails_allEmailsBeingAttemptedToSend() throws Exception {
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
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null).build());
    }
    for (int i = numOfRegistrarsWithExpiringCertificates; i <= numOfRegistrars; i++) {
      persistResource(createRegistrar("goodcert" + i, "name" + i, certificate, null).build());
    }
    assertThat(action.sendNotificationEmails()).isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @TestOfyAndSql
  void sendNotificationEmails_allEmailsBeingAttemptedToSend_onlyMainCertificates()
      throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    for (int i = 1; i <= numOfRegistrars; i++) {
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null).build());
    }
    assertThat(action.sendNotificationEmails()).isEqualTo(numOfRegistrars);
  }

  @TestOfyAndSql
  void sendNotificationEmails_allEmailsBeingAttemptedToSend_onlyFailOverCertificates()
      throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    for (int i = 1; i <= numOfRegistrars; i++) {
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, null, expiringCertificate).build());
    }
    assertThat(action.sendNotificationEmails()).isEqualTo(numOfRegistrars);
  }

  @TestOfyAndSql
  void sendNotificationEmails_allEmailsBeingAttemptedToSend_mixedOfCertificates() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    int numOfExpiringFailOverOnly = 2;
    int numOfExpiringPrimaryOnly = 3;
    for (int i = 1; i <= numOfExpiringFailOverOnly; i++) {
      persistResource(
          createRegistrar("cl" + i, "expiringFailOverOnly" + i, null, expiringCertificate).build());
    }
    for (int i = 1; i <= numOfExpiringPrimaryOnly; i++) {
      persistResource(
          createRegistrar("cli" + i, "expiringPrimaryOnly" + i, expiringCertificate, null).build());
    }
    for (int i = numOfExpiringFailOverOnly + numOfExpiringPrimaryOnly + 1;
        i <= numOfRegistrars;
        i++) {
      persistResource(
          createRegistrar("client" + i, "regularReg" + i, expiringCertificate, expiringCertificate)
              .build());
    }
    assertThat(action.sendNotificationEmails())
        .isEqualTo(numOfRegistrars + numOfExpiringFailOverOnly + numOfExpiringPrimaryOnly);
  }

  @TestOfyAndSql
  void updateLastNotificationSentDate_updatedSuccessfully_primaryCertificate() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-02T00:00:00Z"))
            .cert();
    Registrar registrar =
        createRegistrar("testClientId", "registrar", expiringCertificate, null).build();
    persistResource(registrar);
    action.updateLastNotificationSentDate(registrar, clock.nowUtc(), CertificateType.PRIMARY);
    assertThat(loadByEntity(registrar).getLastExpiringCertNotificationSentDate())
        .isEqualTo(clock.nowUtc());
  }

  @TestOfyAndSql
  void updateLastNotificationSentDate_updatedSuccessfully_failOverCertificate() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Registrar registrar =
        createRegistrar("testClientId", "registrar", null, expiringCertificate).build();
    persistResource(registrar);
    action.updateLastNotificationSentDate(registrar, clock.nowUtc(), CertificateType.FAILOVER);
    assertThat(loadByEntity(registrar).getLastExpiringFailoverCertNotificationSentDate())
        .isEqualTo(clock.nowUtc());
  }

  @TestOfyAndSql
  void updateLastNotificationSentDate_noUpdates_noLastNotificationSentDate() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Registrar registrar =
        createRegistrar("testClientId", "registrar", null, expiringCertificate).build();
    persistResource(registrar);
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> action.updateLastNotificationSentDate(registrar, null, CertificateType.FAILOVER));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Failed to update the last notification sent date to Registrar");
  }

  @TestOfyAndSql
  void updateLastNotificationSentDate_noUpdates_invalidCertificateType() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Registrar registrar =
        createRegistrar("testClientId", "registrar", null, expiringCertificate).build();
    persistResource(registrar);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                action.updateLastNotificationSentDate(
                    registrar, clock.nowUtc(), CertificateType.valueOf("randomType")));
    assertThat(thrown).hasMessageThat().contains("No enum constant");
  }

  @TestOfyAndSql
  void getRegistrarsWithExpiringCertificates_returnsPartOfRegistrars() throws Exception {
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
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null).build());
    }
    for (int i = numOfRegistrarsWithExpiringCertificates; i <= numOfRegistrars; i++) {
      persistResource(createRegistrar("goodcert" + i, "name" + i, certificate, null).build());
    }

    ImmutableList<RegistrarInfo> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results.size()).isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @TestOfyAndSql
  void getRegistrarsWithExpiringCertificates_returnsPartOfRegistrars_failOverCertificateBranch()
      throws Exception {
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
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, null, expiringCertificate).build());
    }
    for (int i = numOfRegistrarsWithExpiringCertificates; i <= numOfRegistrars; i++) {
      persistResource(createRegistrar("goodcert" + i, "name" + i, null, certificate).build());
    }

    assertThat(action.getRegistrarsWithExpiringCertificates().size())
        .isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @TestOfyAndSql
  void getRegistrarsWithExpiringCertificates_returnsAllRegistrars() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();

    int numOfRegistrarsWithExpiringCertificates = 5;
    for (int i = 1; i <= numOfRegistrarsWithExpiringCertificates; i++) {
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null).build());
    }
    assertThat(action.getRegistrarsWithExpiringCertificates().size())
        .isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @TestOfyAndSql
  void getRegistrarsWithExpiringCertificates_returnsNoRegistrars() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    for (int i = 1; i <= numOfRegistrars; i++) {
      persistResource(createRegistrar("goodcert" + i, "name" + i, certificate, null).build());
    }
    assertThat(action.getRegistrarsWithExpiringCertificates()).isEmpty();
  }

  @TestOfyAndSql
  void getRegistrarsWithExpiringCertificates_noRegistrarsInDatabase() {
    assertThat(action.getRegistrarsWithExpiringCertificates()).isEmpty();
  }

  @TestOfyAndSql
  void getEmailAddresses_success_returnsAnEmptyList() {
    assertThat(action.getEmailAddresses(sampleRegistrar, Type.TECH)).isEmpty();
    assertThat(action.getEmailAddresses(sampleRegistrar, Type.ADMIN)).isEmpty();
  }

  @TestOfyAndSql
  void getEmailAddresses_success_returnsAListOfEmails() throws Exception {
    Registrar registrar = persistResource(makeRegistrar1());
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
    assertThat(action.getEmailAddresses(registrar, Type.TECH))
        .containsExactly(
            new InternetAddress("will@example-registrar.tld"),
            new InternetAddress("jd@example-registrar.tld"),
            new InternetAddress("js@example-registrar.tld"));
    assertThat(action.getEmailAddresses(registrar, Type.ADMIN))
        .containsExactly(
            new InternetAddress("janedoe@theregistrar.com"), // comes with makeRegistrar1()
            new InternetAddress("mike@example-registrar.tld"),
            new InternetAddress("john@example-registrar.tld"));
  }

  @TestOfyAndSql
  void getEmailAddresses_failure_returnsPartialListOfEmails_skipInvalidEmails() {
    // when building a new RegistrarContact object, there's already an email validation process.
    // if the registrarContact is created successful, the email address of the contact object
    // should already be validated. Ideally, there should not be an AddressException when creating
    // a new InternetAddress using the email address string of the contact object.
  }

  @TestOfyAndSql
  void getEmailBody_returnsEmailBodyText() {
    String registrarName = "good registrar";
    String certExpirationDateStr = "2021-06-15";
    CertificateType certificateType = CertificateType.PRIMARY;
    String registrarId = "registrarid";
    String emailBody =
        action.getEmailBody(
            registrarName,
            certificateType,
            DateTime.parse(certExpirationDateStr).toDate(),
            registrarId);
    assertThat(emailBody).contains(registrarName);
    assertThat(emailBody).contains(certificateType.getDisplayName());
    assertThat(emailBody).contains(certExpirationDateStr);
    assertThat(emailBody).contains(registrarId + "@registry.google");
    assertThat(emailBody).doesNotContain("%1$s@registry.google");
    assertThat(emailBody).doesNotContain("%2$s@registry.google");
    assertThat(emailBody).doesNotContain("%3$s@registry.google");
    assertThat(emailBody).doesNotContain("%4$s@registry.google");
  }

  @TestOfyAndSql
  void getEmailBody_throwsIllegalArgumentException_noExpirationDate() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                action.getEmailBody(
                    "good registrar", CertificateType.FAILOVER, null, "registrarId"));
    assertThat(thrown).hasMessageThat().contains("Expiration date cannot be null");
  }

  @TestOfyAndSql
  void getEmailBody_throwsIllegalArgumentException_noCertificateType() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                action.getEmailBody(
                    "good registrar", null, DateTime.parse("2021-06-15").toDate(), "registrarId"));
    assertThat(thrown).hasMessageThat().contains("Certificate type cannot be null");
  }

  @TestOfyAndSql
  void getEmailBody_throwsIllegalArgumentException_noRegistrarId() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                action.getEmailBody(
                    "good registrar",
                    CertificateType.FAILOVER,
                    DateTime.parse("2021-06-15").toDate(),
                    null));
    assertThat(thrown).hasMessageThat().contains("Registrar Id cannot be null");
  }
}
