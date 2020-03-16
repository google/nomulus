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

package google.registry.ui.server.registrar;

import static com.google.common.net.HttpHeaders.LOCATION;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.GaeUserIdConverter.convertEmailAddressToGaeUserId;
import static google.registry.model.registrar.Registrar.loadByClientId;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.request.Action.Method;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.UserAuthInfo;
import google.registry.security.XsrfTokenManager;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.SystemPropertyRule;
import google.registry.ui.server.SendEmailUtils;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.util.Optional;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletRequest;
import org.joda.money.CurrencyUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class ConsoleRegistrarCreatorActionTest {

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Rule
  public final SystemPropertyRule systemPropertyRule = new SystemPropertyRule();

  private final FakeResponse response = new FakeResponse();
  private final ConsoleRegistrarCreatorAction action = new ConsoleRegistrarCreatorAction();
  private final User user = new User("marla.singer@example.com", "gmail.com", "12345");

  @Mock HttpServletRequest request;
  @Mock SendEmailService emailService;

  @Before
  public void setUp() throws Exception {
    persistPremiumList("default_sandbox_list", "sandbox,USD 1000");

    action.req = request;
    action.method = Method.GET;
    action.response = response;
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of("unused", AuthenticatedRegistrarAccessor.Role.ADMIN));
    action.userService = UserServiceFactory.getUserService();
    action.xsrfTokenManager = new XsrfTokenManager(new FakeClock(), action.userService);
    action.authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    action.sendEmailUtils =
        new SendEmailUtils(
            new InternetAddress("outgoing@registry.example"),
            "UnitTest Registry",
            ImmutableList.of("notification@test.example", "notification2@test.example"),
            emailService);
    action.logoFilename = "logo.png";
    action.productName = "Nomulus";

    action.clientId = Optional.empty();
    action.name = Optional.empty();
    action.billingAccount = Optional.empty();
    action.ianaId = Optional.empty();
    action.referralEmail = Optional.empty();
    action.driveId = Optional.empty();
    action.consoleUserEmail = Optional.empty();

    action.street1 = Optional.empty();
    action.optionalStreet2 = Optional.empty();
    action.optionalStreet3 = Optional.empty();
    action.city = Optional.empty();
    action.optionalState = Optional.empty();
    action.optionalZip = Optional.empty();
    action.countryCode = Optional.empty();

    action.optionalPassword = Optional.empty();
    action.optionalPasscode = Optional.empty();

    action.passwordGenerator = new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");
    action.passcodeGenerator = new DeterministicStringGenerator("314159265");

    action.analyticsConfig = ImmutableMap.of("googleAnalyticsId", "sampleId");
  }

  @Test
  public void testNoUser_redirect() {
    when(request.getRequestURI()).thenReturn("/test");
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_MOVED_TEMPORARILY);
    assertThat(response.getHeaders().get(LOCATION)).isEqualTo("/_ah/login?continue=%2Ftest");
  }

  @Test
  public void testGet_authorized() {
    action.run();
    assertThat(response.getPayload()).contains("<h1>Create Registrar</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }

  @Test
  public void testGet_authorized_onProduction() {
    RegistryEnvironment.PRODUCTION.setup(systemPropertyRule);
    action.run();
    assertThat(response.getPayload()).contains("<h1>Create Registrar</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }

  @Test
  public void testGet_unauthorized() {
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    action.run();
    assertThat(response.getPayload()).contains("<h1>You need permission</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }

  @Test
  public void testPost_authorized_minimalAddress() {
    action.clientId = Optional.of("myclientid");
    action.name = Optional.of("registrar name");
    action.billingAccount = Optional.of("USD=billing-account");
    action.ianaId = Optional.of(12321);
    action.referralEmail = Optional.of("icann@example.com");
    action.driveId = Optional.of("drive-id");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");

    action.street1 = Optional.of("my street");
    action.city = Optional.of("my city");
    action.countryCode = Optional.of("CC");


    action.method = Method.POST;
    action.run();

    assertThat(response.getPayload())
        .contains("<h1>Successfully created Registrar myclientid</h1>");

    ArgumentCaptor<EmailMessage> contentCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emailService).sendEmail(contentCaptor.capture());
    EmailMessage emailMessage = contentCaptor.getValue();
    assertThat(emailMessage.subject()).isEqualTo("Registrar myclientid created in unittest");
    assertThat(emailMessage.body())
        .isEqualTo(
            ""
                + "The following registrar was created in unittest by TestUserId:\n"
                + "    clientId: myclientid\n"
                + "    name: registrar name\n"
                + "    billingAccount: USD=billing-account\n"
                + "    ianaId: 12321\n"
                + "    referralEmail: icann@example.com\n"
                + "    driveId: drive-id\n"
                + "Gave user myclientid@registry.example web access to the registrar\n");
    Registrar registrar = loadByClientId("myclientid").orElse(null);
    assertThat(registrar).isNotNull();
    assertThat(registrar.getClientId()).isEqualTo("myclientid");
    assertThat(registrar.getBillingAccountMap())
        .containsExactly(CurrencyUnit.USD, "billing-account");

    assertThat(registrar.getDriveFolderId()).isEqualTo("drive-id");
    assertThat(registrar.getIanaIdentifier()).isEqualTo(12321L);
    assertThat(registrar.getIcannReferralEmail()).isEqualTo("icann@example.com");
    assertThat(registrar.getEmailAddress()).isEqualTo("icann@example.com");
    assertThat(registrar.verifyPassword("abcdefghijklmnop")).isTrue();
    assertThat(registrar.getPhonePasscode()).isEqualTo("31415");
    assertThat(registrar.getState()).isEqualTo(Registrar.State.PENDING);
    assertThat(registrar.getType()).isEqualTo(Registrar.Type.REAL);

    assertThat(registrar.getLocalizedAddress()).isEqualTo(new RegistrarAddress.Builder()
        .setStreet(ImmutableList.of("my street"))
        .setCity("my city")
        .setCountryCode("CC")
        .build());

    assertThat(registrar.getContacts())
        .containsExactly(
            new RegistrarContact.Builder()
                .setParent(registrar)
                .setGaeUserId("-1509175207")
                .setGaeUserId(convertEmailAddressToGaeUserId("myclientid@registry.example"))
                .setName("myclientid@registry.example")
                .setEmailAddress("myclientid@registry.example")
                .build());
  }

  @Test
  public void testPost_authorized_allAddress() {
    action.clientId = Optional.of("myclientid");
    action.name = Optional.of("registrar name");
    action.billingAccount = Optional.of("USD=billing-account");
    action.ianaId = Optional.of(12321);
    action.referralEmail = Optional.of("icann@example.com");
    action.driveId = Optional.of("drive-id");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");

    action.street1 = Optional.of("my street");
    action.optionalStreet2 = Optional.of("more street");
    action.optionalStreet3 = Optional.of("final street");
    action.city = Optional.of("my city");
    action.optionalState = Optional.of("province");
    action.optionalZip = Optional.of("12345-678");
    action.countryCode = Optional.of("CC");


    action.method = Method.POST;
    action.run();

    assertThat(response.getPayload())
        .contains("<h1>Successfully created Registrar myclientid</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");

    Registrar registrar = loadByClientId("myclientid").orElse(null);
    assertThat(registrar).isNotNull();
    assertThat(registrar.getLocalizedAddress()).isEqualTo(new RegistrarAddress.Builder()
        .setStreet(ImmutableList.of("my street", "more street", "final street"))
        .setCity("my city")
        .setState("province")
        .setZip("12345-678")
        .setCountryCode("CC")
        .build());
  }

  @Test
  public void testPost_authorized_multipleBillingLines() {
    action.clientId = Optional.of("myclientid");
    action.name = Optional.of("registrar name");
    action.ianaId = Optional.of(12321);
    action.referralEmail = Optional.of("icann@example.com");
    action.driveId = Optional.of("drive-id");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");

    action.street1 = Optional.of("my street");
    action.city = Optional.of("my city");
    action.countryCode = Optional.of("CC");

    action.method = Method.POST;

    action.billingAccount =
        Optional.of(
            ""
                + "JPY=billing-account-yen\n"
                + "  Usd = billing-account-usd  \r\n"
                + "\n"
                + "   \n"
                + "eur=billing-account-eur\n");
    action.run();

    assertThat(response.getPayload())
        .contains("<h1>Successfully created Registrar myclientid</h1>");

    Registrar registrar = loadByClientId("myclientid").orElse(null);
    assertThat(registrar).isNotNull();
    assertThat(registrar.getBillingAccountMap())
        .containsExactly(
            CurrencyUnit.JPY, "billing-account-yen",
            CurrencyUnit.USD, "billing-account-usd",
            CurrencyUnit.EUR, "billing-account-eur");
  }

  @Test
  public void testPost_authorized_repeatingCurrency_fails() {
    action.clientId = Optional.of("myclientid");
    action.name = Optional.of("registrar name");
    action.ianaId = Optional.of(12321);
    action.referralEmail = Optional.of("icann@example.com");
    action.driveId = Optional.of("drive-id");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");

    action.street1 = Optional.of("my street");
    action.city = Optional.of("my city");
    action.countryCode = Optional.of("CC");

    action.method = Method.POST;

    action.billingAccount =
        Optional.of(
            ""
                + "JPY=billing-account-1\n"
                + "jpy=billing-account-2\n");
    action.run();

    assertThat(response.getPayload())
        .contains(
            "Failed: Error parsing billing accounts - Multiple entries with same key:"
                + " JPY=billing-account-2 and JPY=billing-account-1");
  }

  @Test
  public void testPost_authorized_badCurrency_fails() {
    action.clientId = Optional.of("myclientid");
    action.name = Optional.of("registrar name");
    action.ianaId = Optional.of(12321);
    action.referralEmail = Optional.of("icann@example.com");
    action.driveId = Optional.of("drive-id");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");

    action.street1 = Optional.of("my street");
    action.city = Optional.of("my city");
    action.countryCode = Optional.of("CC");

    action.method = Method.POST;

    action.billingAccount =
        Optional.of(
            ""
                + "JPY=billing-account-1\n"
                + "xyz=billing-account-2\n"
                + "usd=billing-account-3\n");
    action.run();

    assertThat(response.getPayload())
        .contains("Failed: Error parsing billing accounts - Unknown currency &#39;XYZ&#39;");
  }

  @Test
  public void testPost_authorized_badBillingLine_fails() {
    action.clientId = Optional.of("myclientid");
    action.name = Optional.of("registrar name");
    action.ianaId = Optional.of(12321);
    action.referralEmail = Optional.of("icann@example.com");
    action.driveId = Optional.of("drive-id");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");

    action.street1 = Optional.of("my street");
    action.city = Optional.of("my city");
    action.countryCode = Optional.of("CC");

    action.method = Method.POST;

    action.billingAccount =
        Optional.of(
            ""
                + "JPY=billing-account-1\n"
                + "some bad line\n"
                + "usd=billing-account-2\n");
    action.run();

    assertThat(response.getPayload())
        .contains(
            "Failed: Error parsing billing accounts - Can&#39;t parse line [some bad line]."
                + " The format should be [currency]=[account ID]");
  }

  @Test
  public void testPost_authorized_setPassword() {
    action.clientId = Optional.of("myclientid");
    action.name = Optional.of("registrar name");
    action.billingAccount = Optional.of("USD=billing-account");
    action.ianaId = Optional.of(12321);
    action.referralEmail = Optional.of("icann@example.com");
    action.driveId = Optional.of("drive-id");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");

    action.street1 = Optional.of("my street");
    action.city = Optional.of("my city");
    action.countryCode = Optional.of("CC");

    action.optionalPassword = Optional.of("SomePassword");
    action.optionalPasscode = Optional.of("10203");

    action.method = Method.POST;
    action.run();

    assertThat(response.getPayload())
        .contains("<h1>Successfully created Registrar myclientid</h1>");

    Registrar registrar = loadByClientId("myclientid").orElse(null);
    assertThat(registrar).isNotNull();
    assertThat(registrar.verifyPassword("SomePassword")).isTrue();
    assertThat(registrar.getPhonePasscode()).isEqualTo("10203");
  }

  @Test
  public void testPost_badEmailFails() {
    action.clientId = Optional.of("myclientid");
    action.name = Optional.of("registrar name");
    action.billingAccount = Optional.of("USD=billing-account");
    action.ianaId = Optional.of(12321);
    action.referralEmail = Optional.of("lolcat");
    action.driveId = Optional.of("drive-id");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");

    action.street1 = Optional.of("my street");
    action.city = Optional.of("my city");
    action.countryCode = Optional.of("CC");

    action.method = Method.POST;
    action.run();

    assertThat(response.getPayload())
        .contains("Failed: Provided email lolcat is not a valid email address");
  }

  @Test
  public void testPost_unauthorized() {
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());
    action.clientId = Optional.of("myclientid");
    action.consoleUserEmail = Optional.of("myclientid@registry.example");
    action.method = Method.POST;
    action.run();
    assertThat(response.getPayload()).contains("<h1>You need permission</h1>");
    assertThat(response.getPayload()).contains("gtag('config', 'sampleId')");
  }
}
