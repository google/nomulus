// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistEppResource;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static org.joda.money.CurrencyUnit.USD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.TestLogHandler;
import google.registry.groups.GmailClient;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.contact.Contact;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.domain.token.BulkPricingPackage;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.ui.server.SendEmailUtils;
import google.registry.util.EmailMessage;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link CheckBulkComplianceAction}. */
public class CheckBulkComplianceActionTest {
  // This is the default creation time for test data.
  private final FakeClock clock = new FakeClock(DateTime.parse("2012-03-25TZ"));
  private static final String CREATE_LIMIT_EMAIL_SUBJECT = "create limit subject";
  private static final String DOMAIN_LIMIT_WARNING_EMAIL_SUBJECT = "domain limit warning subject";
  private static final String DOMAIN_LIMIT_UPGRADE_EMAIL_SUBJECT = "domain limit upgrade subject";
  private static final String CREATE_LIMIT_EMAIL_BODY =
      "create limit body %1$s %2$s %3$s %4$s %5$s";
  private static final String DOMAIN_LIMIT_WARNING_EMAIL_BODY =
      "domain limit warning body %1$s %2$s %3$s %4$s %5$s";
  private static final String DOMAIN_LIMIT_UPGRADE_EMAIL_BODY =
      "domain limit upgrade body %1$s %2$s %3$s %4$s %5$s";
  private static final String SUPPORT_EMAIL = "registry@test.com";

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private CheckBulkComplianceAction action;
  private AllocationToken token;
  private final TestLogHandler logHandler = new TestLogHandler();
  private final Logger loggerToIntercept =
      Logger.getLogger(CheckBulkComplianceAction.class.getCanonicalName());
  private final GmailClient gmailClient = mock(GmailClient.class);
  private Contact contact;
  private BulkPricingPackage bulkPricingPackage;
  private SendEmailUtils sendEmailUtils;
  private ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);

  @BeforeEach
  void beforeEach() throws Exception {
    loggerToIntercept.addHandler(logHandler);
    sendEmailUtils =
        new SendEmailUtils(
            ImmutableList.of("notification@test.example", "notification2@test.example"),
            gmailClient);
    createTld("tld");
    action =
        new CheckBulkComplianceAction(
            sendEmailUtils,
            clock,
            CREATE_LIMIT_EMAIL_SUBJECT,
            DOMAIN_LIMIT_WARNING_EMAIL_SUBJECT,
            DOMAIN_LIMIT_UPGRADE_EMAIL_SUBJECT,
            CREATE_LIMIT_EMAIL_BODY,
            DOMAIN_LIMIT_WARNING_EMAIL_BODY,
            DOMAIN_LIMIT_UPGRADE_EMAIL_BODY,
            SUPPORT_EMAIL);
    token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.BULK_PRICING)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(CurrencyUnit.USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(1.0)
                .build());
    bulkPricingPackage =
        new BulkPricingPackage.Builder()
            .setToken(token)
            .setMaxDomains(3)
            .setMaxCreates(1)
            .setBulkPrice(Money.of(CurrencyUnit.USD, 1000))
            .setNextBillingDate(DateTime.parse("2012-11-12T05:00:00Z"))
            .setLastNotificationSent(DateTime.parse("2010-11-12T05:00:00Z"))
            .build();

    contact = persistActiveContact("contact1234");
  }

  @AfterEach
  void afterEach() {
    loggerToIntercept.removeHandler(logHandler);
  }

  @Test
  void testSuccess_noBulkPackageOverCreateLimit() {
    tm().transact(() -> tm().put(bulkPricingPackage));
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    action.run();
    verifyNoInteractions(gmailClient);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found no bulk pricing packages over their create limit.");
  }

  @Test
  void testSuccess_oneBulkPackageOverCreateLimit() throws Exception {
    tm().transact(() -> tm().put(bulkPricingPackage));
    // Create limit is 1, creating 2 domains to go over the limit
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found 1 bulk pricing packages over their create limit.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token abc123 has exceeded their max domain creation"
                + " limit by 1 name(s).");
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage emailMessage = emailCaptor.getValue();
    assertThat(emailMessage.subject()).isEqualTo(CREATE_LIMIT_EMAIL_SUBJECT);
    assertThat(emailMessage.body())
        .isEqualTo(String.format(CREATE_LIMIT_EMAIL_BODY, 1, "abc123", "The Registrar", 1, 2));
  }

  @Test
  void testSuccess_multipleBulkPricingPackagesOverCreateLimit() {
    tm().transact(() -> tm().put(bulkPricingPackage));
    // Create limit is 1, creating 2 domains to go over the limit
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    AllocationToken token2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("token")
                .setTokenType(TokenType.BULK_PRICING)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(1.0)
                .build());
    BulkPricingPackage bulkPricingPackage2 =
        new BulkPricingPackage.Builder()
            .setToken(token2)
            .setMaxDomains(8)
            .setMaxCreates(1)
            .setBulkPrice(Money.of(CurrencyUnit.USD, 1000))
            .setNextBillingDate(DateTime.parse("2012-11-12T05:00:00Z"))
            .build();
    tm().transact(() -> tm().put(bulkPricingPackage2));

    persistEppResource(
        DatabaseHelper.newDomain("foo2.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token2.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz2.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token2.createVKey())
            .build());
    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found 2 bulk pricing packages over their create limit.");

    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token abc123 has exceeded their max domain creation"
                + " limit by 1 name(s).");

    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token token has exceeded their max domain creation"
                + " limit by 1 name(s).");
    verify(gmailClient, times(2)).sendEmail(any(EmailMessage.class));
  }

  @Test
  void testSuccess_onlyChecksCurrentBillingYear() {
    tm().transact(() -> tm().put(bulkPricingPackage));
    AllocationToken token2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("token")
                .setTokenType(TokenType.BULK_PRICING)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(1.0)
                .build());
    BulkPricingPackage packagePromotion2 =
        new BulkPricingPackage.Builder()
            .setToken(token2)
            .setMaxDomains(8)
            .setMaxCreates(1)
            .setBulkPrice(Money.of(CurrencyUnit.USD, 1000))
            .setNextBillingDate(DateTime.parse("2015-11-12T05:00:00Z"))
            .build();
    tm().transact(() -> tm().put(packagePromotion2));

    // Create limit is 1, creating 2 domains to go over the limit
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token2.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token2.createVKey())
            .build());

    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found no bulk pricing packages over their create limit.");
    verifyNoInteractions(gmailClient);
  }

  @Test
  void testSuccess_noBulkPricingPackageOverActiveDomainsLimit() {
    tm().transact(() -> tm().put(bulkPricingPackage));
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    action.run();
    verifyNoInteractions(gmailClient);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found no bulk pricing packages over their active domains limit.");
  }

  @Test
  void testSuccess_oneBulkPricingPackageOverActiveDomainsLimit() {
    bulkPricingPackage = bulkPricingPackage.asBuilder().setMaxCreates(4).setMaxDomains(1).build();
    tm().transact(() -> tm().put(bulkPricingPackage));
    // Domains limit is 1, creating 2 domains to go over the limit
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    AllocationToken token2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("token")
                .setTokenType(TokenType.BULK_PRICING)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(1.0)
                .build());
    BulkPricingPackage bulkPricingPackage2 =
        new BulkPricingPackage.Builder()
            .setToken(token2)
            .setMaxDomains(8)
            .setMaxCreates(4)
            .setBulkPrice(Money.of(CurrencyUnit.USD, 1000))
            .setNextBillingDate(DateTime.parse("2012-11-12T05:00:00Z"))
            .build();
    tm().transact(() -> tm().put(bulkPricingPackage2));
    persistEppResource(
        DatabaseHelper.newDomain("foo2.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token2.createVKey())
            .build());

    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found 1 bulk pricing packages over their active domains limit.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token abc123 has exceed their max active domains limit"
                + " by 1 name(s).");
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage emailMessage = emailCaptor.getValue();
    assertThat(emailMessage.subject()).isEqualTo(DOMAIN_LIMIT_WARNING_EMAIL_SUBJECT);
    assertThat(emailMessage.body())
        .isEqualTo(
            String.format(DOMAIN_LIMIT_WARNING_EMAIL_BODY, 1, "abc123", "The Registrar", 1, 2));
    BulkPricingPackage packageAfterCheck =
        tm().transact(() -> BulkPricingPackage.loadByTokenString(token.getToken()).get());
    assertThat(packageAfterCheck.getLastNotificationSent().get()).isEqualTo(clock.nowUtc());
  }

  @Test
  void testSuccess_multipleBulkPricingPackagesOverActiveDomainsLimit() {
    tm().transact(
            () ->
                tm().put(bulkPricingPackage.asBuilder().setMaxDomains(1).setMaxCreates(4).build()));
    // Domains limit is 1, creating 2 domains to go over the limit
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    AllocationToken token2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("token")
                .setTokenType(TokenType.BULK_PRICING)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(1.0)
                .build());
    BulkPricingPackage bulkPricingPackage2 =
        new BulkPricingPackage.Builder()
            .setToken(token2)
            .setMaxDomains(1)
            .setMaxCreates(5)
            .setBulkPrice(Money.of(CurrencyUnit.USD, 1000))
            .setNextBillingDate(DateTime.parse("2012-11-12T05:00:00Z"))
            .build();
    tm().transact(() -> tm().put(bulkPricingPackage2));

    persistEppResource(
        DatabaseHelper.newDomain("foo2.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token2.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz2.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token2.createVKey())
            .build());

    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found 2 bulk pricing packages over their active domains limit.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token abc123 has exceed their max active domains limit"
                + " by 1 name(s).");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token token has exceed their max active domains limit"
                + " by 1 name(s).");
    verify(gmailClient, times(2)).sendEmail(any(EmailMessage.class));
  }

  @Test
  void
      testSuccess_bulkPricingPackageOverActiveDomainsLimitAlreadySentWarningEmail_DoesNotSendAgain() {
    bulkPricingPackage =
        bulkPricingPackage
            .asBuilder()
            .setMaxCreates(4)
            .setMaxDomains(1)
            .setLastNotificationSent(clock.nowUtc().minusDays(5))
            .build();
    tm().transact(() -> tm().put(bulkPricingPackage));
    // Domains limit is 1, creating 2 domains to go over the limit
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found 1 bulk pricing packages over their active domains limit.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token abc123 has exceed their max active domains limit"
                + " by 1 name(s).");
    verifyNoInteractions(gmailClient);
    BulkPricingPackage packageAfterCheck =
        tm().transact(() -> BulkPricingPackage.loadByTokenString(token.getToken()).get());
    assertThat(packageAfterCheck.getLastNotificationSent().get())
        .isEqualTo(clock.nowUtc().minusDays(5));
  }

  @Test
  void
      testSuccess_bulkPricingPackageOverActiveDomainsLimitAlreadySentWarningEmailOver40DaysAgo_SendsAgain() {
    bulkPricingPackage =
        bulkPricingPackage
            .asBuilder()
            .setMaxCreates(4)
            .setMaxDomains(1)
            .setLastNotificationSent(clock.nowUtc().minusDays(45))
            .build();
    tm().transact(() -> tm().put(bulkPricingPackage));
    // Domains limit is 1, creating 2 domains to go over the limit
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found 1 bulk pricing packages over their active domains limit.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token abc123 has exceed their max active domains limit"
                + " by 1 name(s).");
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage emailMessage = emailCaptor.getValue();
    assertThat(emailMessage.subject()).isEqualTo(DOMAIN_LIMIT_WARNING_EMAIL_SUBJECT);
    assertThat(emailMessage.body())
        .isEqualTo(
            String.format(DOMAIN_LIMIT_WARNING_EMAIL_BODY, 1, "abc123", "The Registrar", 1, 2));
    BulkPricingPackage packageAfterCheck =
        tm().transact(() -> BulkPricingPackage.loadByTokenString(token.getToken()).get());
    assertThat(packageAfterCheck.getLastNotificationSent().get()).isEqualTo(clock.nowUtc());
  }

  @Test
  void
      testSuccess_bulkPricingPackageOverActiveDomainsLimitAlreadySentWarning30DaysAgo_SendsUpgradeEmail() {
    bulkPricingPackage =
        bulkPricingPackage
            .asBuilder()
            .setMaxCreates(4)
            .setMaxDomains(1)
            .setLastNotificationSent(clock.nowUtc().minusDays(31))
            .build();
    tm().transact(() -> tm().put(bulkPricingPackage));
    // Domains limit is 1, creating 2 domains to go over the limit
    persistEppResource(
        DatabaseHelper.newDomain("foo.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());
    persistEppResource(
        DatabaseHelper.newDomain("buzz.tld", contact)
            .asBuilder()
            .setCurrentBulkToken(token.createVKey())
            .build());

    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Found 1 bulk pricing packages over their active domains limit.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Bulk pricing package with bulk token abc123 has exceed their max active domains limit"
                + " by 1 name(s).");
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage emailMessage = emailCaptor.getValue();
    assertThat(emailMessage.subject()).isEqualTo(DOMAIN_LIMIT_UPGRADE_EMAIL_SUBJECT);
    assertThat(emailMessage.body())
        .isEqualTo(
            String.format(DOMAIN_LIMIT_UPGRADE_EMAIL_BODY, 1, "abc123", "The Registrar", 1, 2));
    BulkPricingPackage packageAfterCheck =
        tm().transact(() -> BulkPricingPackage.loadByTokenString(token.getToken()).get());
    assertThat(packageAfterCheck.getLastNotificationSent().get()).isEqualTo(clock.nowUtc());
  }
}
