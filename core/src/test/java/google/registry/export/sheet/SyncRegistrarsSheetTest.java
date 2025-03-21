// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export.sheet;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.config.RegistryConfig.getDefaultRegistrarWhoisServer;
import static google.registry.model.common.Cursor.CursorType.SYNC_REGISTRAR_SHEET;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardMinutes;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.common.Cursor;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link SyncRegistrarsSheet}. */
@ExtendWith(MockitoExtension.class)
public class SyncRegistrarsSheetTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Captor private ArgumentCaptor<ImmutableList<ImmutableMap<String, String>>> rowsCaptor;
  @Mock private SheetSynchronizer sheetSynchronizer;

  private final FakeClock clock = new FakeClock(DateTime.now(UTC));

  private SyncRegistrarsSheet newSyncRegistrarsSheet() {
    SyncRegistrarsSheet result = new SyncRegistrarsSheet();
    result.clock = clock;
    result.sheetSynchronizer = sheetSynchronizer;
    return result;
  }

  @BeforeEach
  void beforeEach() {
    createTld("example");
    // Remove Registrar entities created by JpaTransactionManagerExtension.
    tm().transact(() -> tm().loadAllOf(RegistrarPoc.class)).forEach(DatabaseHelper::deleteResource);
    Registrar.loadAll().forEach(DatabaseHelper::deleteResource);
  }

  @Test
  void test_wereRegistrarsModified_noRegistrars_returnsFalse() {
    assertThat(newSyncRegistrarsSheet().wereRegistrarsModified()).isFalse();
  }

  @Test
  void test_wereRegistrarsModified_atDifferentCursorTimes() {
    persistNewRegistrar("SomeRegistrar", "Some Registrar Inc.", Registrar.Type.REAL, 8L);
    persistResource(Cursor.createGlobal(SYNC_REGISTRAR_SHEET, clock.nowUtc().minusHours(1)));
    assertThat(newSyncRegistrarsSheet().wereRegistrarsModified()).isTrue();
    persistResource(Cursor.createGlobal(SYNC_REGISTRAR_SHEET, clock.nowUtc().plusHours(1)));
    assertThat(newSyncRegistrarsSheet().wereRegistrarsModified()).isFalse();
  }

  @Test
  void testRun() throws Exception {
    persistResource(
        new Registrar.Builder()
            .setRegistrarId("anotherregistrar")
            .setRegistrarName("Another Registrar LLC")
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(1L)
            .setState(Registrar.State.ACTIVE)
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("I will get ignored :'("))
                    .setCity("Williamsburg")
                    .setState("NY")
                    .setZip("11211")
                    .setCountryCode("US")
                    .build())
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("123 Main St", "Suite 100"))
                    .setCity("Smalltown")
                    .setState("NY")
                    .setZip("11211")
                    .setCountryCode("US")
                    .build())
            .setPhoneNumber("+1.2125551212")
            .setFaxNumber("+1.2125551213")
            .setEmailAddress("contact-us@example.com")
            .setWhoisServer("whois.example.com")
            .setUrl("http://www.example.org/another_registrar")
            .setIcannReferralEmail("jim@example.net")
            .build());

    Registrar registrar =
        new Registrar.Builder()
            .setRegistrarId("aaaregistrar")
            .setRegistrarName("AAA Registrar Inc.")
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.SUSPENDED)
            .setPassword("pa$$word")
            .setEmailAddress("nowhere@example.org")
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(
                        ImmutableList.of("I get fallen back upon since there's no l10n addr"))
                    .setCity("Williamsburg")
                    .setState("NY")
                    .setZip("11211")
                    .setCountryCode("US")
                    .build())
            .setAllowedTlds(ImmutableSet.of("example"))
            .setPhoneNumber("+1.2223334444")
            .setUrl("http://www.example.org/aaa_registrar")
            .setBillingAccountMap(ImmutableMap.of(USD, "USD1234", JPY, "JPY7890"))
            .build();
    ImmutableList<RegistrarPoc> contacts =
        ImmutableList.of(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Jane Doe")
                .setEmailAddress("contact@example.com")
                .setPhoneNumber("+1.1234567890")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.ADMIN, RegistrarPoc.Type.BILLING))
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("John Doe")
                .setEmailAddress("john.doe@example.tld")
                .setPhoneNumber("+1.1234567890")
                .setFaxNumber("+1.1234567891")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.ADMIN))
                // Purposely flip the internal/external admin/tech
                // distinction to make sure we're not relying on it.  Sigh.
                .setVisibleInWhoisAsAdmin(false)
                .setVisibleInWhoisAsTech(true)
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Jane Smith")
                .setEmailAddress("pride@example.net")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH))
                .build());
    // Use registrar key for contacts' parent.
    DateTime registrarCreationTime = persistResource(registrar).getCreationTime();
    persistSimpleResources(contacts);

    clock.advanceBy(standardMinutes(1));
    newSyncRegistrarsSheet().run("foobar");

    verify(sheetSynchronizer).synchronize(eq("foobar"), rowsCaptor.capture());
    ImmutableList<ImmutableMap<String, String>> rows = getOnlyElement(rowsCaptor.getAllValues());
    assertThat(rows).hasSize(2);

    ImmutableMap<String, String> row = rows.getFirst();
    assertThat(row).containsEntry("registrarId", "aaaregistrar");
    assertThat(row).containsEntry("registrarName", "AAA Registrar Inc.");
    assertThat(row).containsEntry("state", "SUSPENDED");
    assertThat(row).containsEntry("ianaIdentifier", "8");
    assertThat(row)
        .containsEntry(
            "primaryContacts",
            """
            Jane Doe
            contact@example.com
            Tel: +1.1234567890
            Types: [ADMIN, BILLING]
            Visible in registrar WHOIS query as Admin contact: No
            Visible in registrar WHOIS query as Technical contact: No
            Phone number and email visible in domain WHOIS query as Registrar Abuse contact\
             info: No

            John Doe
            john.doe@example.tld
            Tel: +1.1234567890
            Fax: +1.1234567891
            Types: [ADMIN]
            Visible in registrar WHOIS query as Admin contact: No
            Visible in registrar WHOIS query as Technical contact: Yes
            Phone number and email visible in domain WHOIS query as Registrar Abuse contact\
             info: No
            """);
    assertThat(row)
        .containsEntry(
            "techContacts",
            """
            Jane Smith
            pride@example.net
            Types: [TECH]
            Visible in registrar WHOIS query as Admin contact: No
            Visible in registrar WHOIS query as Technical contact: No
            Phone number and email visible in domain WHOIS query as Registrar Abuse contact\
             info: No
            """);
    assertThat(row).containsEntry("marketingContacts", "");
    assertThat(row).containsEntry("abuseContacts", "");
    assertThat(row).containsEntry("whoisInquiryContacts", "");
    assertThat(row).containsEntry("legalContacts", "");
    assertThat(row)
        .containsEntry(
            "billingContacts",
            """
            Jane Doe
            contact@example.com
            Tel: +1.1234567890
            Types: [ADMIN, BILLING]
            Visible in registrar WHOIS query as Admin contact: No
            Visible in registrar WHOIS query as Technical contact: No
            Phone number and email visible in domain WHOIS query as Registrar Abuse contact\
             info: No
            """);
    assertThat(row).containsEntry("contactsMarkedAsWhoisAdmin", "");
    assertThat(row)
        .containsEntry(
            "contactsMarkedAsWhoisTech",
            """
            John Doe
            john.doe@example.tld
            Tel: +1.1234567890
            Fax: +1.1234567891
            Types: [ADMIN]
            Visible in registrar WHOIS query as Admin contact: No
            Visible in registrar WHOIS query as Technical contact: Yes
            Phone number and email visible in domain WHOIS query as Registrar Abuse contact\
             info: No
            """);
    assertThat(row).containsEntry("emailAddress", "nowhere@example.org");
    assertThat(row).containsEntry(
        "address.street", "I get fallen back upon since there's no l10n addr");
    assertThat(row).containsEntry("address.city", "Williamsburg");
    assertThat(row).containsEntry("address.state", "NY");
    assertThat(row).containsEntry("address.zip", "11211");
    assertThat(row).containsEntry("address.countryCode", "US");
    assertThat(row).containsEntry("phoneNumber", "+1.2223334444");
    assertThat(row).containsEntry("faxNumber", "");
    assertThat(row.get("creationTime")).isEqualTo(registrarCreationTime.toString());
    assertThat(row.get("lastUpdateTime")).isEqualTo(registrarCreationTime.toString());
    assertThat(row).containsEntry("allowedTlds", "example");
    assertThat(row).containsEntry("blockPremiumNames", "false");
    assertThat(row).containsEntry("ipAddressAllowList", "");
    assertThat(row).containsEntry("url", "http://www.example.org/aaa_registrar");
    assertThat(row).containsEntry("icannReferralEmail", "");
    assertThat(row).containsEntry("whoisServer", getDefaultRegistrarWhoisServer());
    assertThat(row).containsEntry("referralUrl", "http://www.example.org/aaa_registrar");
    assertThat(row).containsEntry("billingAccountMap", "{JPY=JPY7890, USD=USD1234}");

    row = rows.get(1);
    assertThat(row).containsEntry("registrarId", "anotherregistrar");
    assertThat(row).containsEntry("registrarName", "Another Registrar LLC");
    assertThat(row).containsEntry("state", "ACTIVE");
    assertThat(row).containsEntry("ianaIdentifier", "1");
    assertThat(row).containsEntry("primaryContacts", "");
    assertThat(row).containsEntry("techContacts", "");
    assertThat(row).containsEntry("marketingContacts", "");
    assertThat(row).containsEntry("abuseContacts", "");
    assertThat(row).containsEntry("whoisInquiryContacts", "");
    assertThat(row).containsEntry("legalContacts", "");
    assertThat(row).containsEntry("billingContacts", "");
    assertThat(row).containsEntry("contactsMarkedAsWhoisAdmin", "");
    assertThat(row).containsEntry("contactsMarkedAsWhoisTech", "");
    assertThat(row).containsEntry("emailAddress", "contact-us@example.com");
    assertThat(row).containsEntry("address.street", "123 Main St\nSuite 100");
    assertThat(row).containsEntry("address.city", "Smalltown");
    assertThat(row).containsEntry("address.state", "NY");
    assertThat(row).containsEntry("address.zip", "11211");
    assertThat(row).containsEntry("address.countryCode", "US");
    assertThat(row).containsEntry("phoneNumber", "+1.2125551212");
    assertThat(row).containsEntry("faxNumber", "+1.2125551213");
    assertThat(row.get("creationTime")).isEqualTo(registrarCreationTime.toString());
    assertThat(row.get("lastUpdateTime")).isEqualTo(registrarCreationTime.toString());
    assertThat(row).containsEntry("allowedTlds", "");
    assertThat(row).containsEntry("whoisServer", "whois.example.com");
    assertThat(row).containsEntry("blockPremiumNames", "false");
    assertThat(row).containsEntry("ipAddressAllowList", "");
    assertThat(row).containsEntry("url", "http://www.example.org/another_registrar");
    assertThat(row).containsEntry("referralUrl", "http://www.example.org/another_registrar");
    assertThat(row).containsEntry("icannReferralEmail", "jim@example.net");
    assertThat(row).containsEntry("billingAccountMap", "{}");

    Cursor cursor = loadByKey(Cursor.createGlobalVKey(SYNC_REGISTRAR_SHEET));
    assertThat(cursor).isNotNull();
    assertThat(cursor.getCursorTime()).isGreaterThan(registrarCreationTime);
  }

  @Test
  void testRun_missingValues_stillWorks() throws Exception {
    persistResource(
        persistNewRegistrar("SomeRegistrar", "Some Registrar", Registrar.Type.REAL, 8L)
            .asBuilder()
            .setBillingAccountMap(ImmutableMap.of())
            .build());

    newSyncRegistrarsSheet().run("foobar");

    verify(sheetSynchronizer).synchronize(eq("foobar"), rowsCaptor.capture());
    ImmutableMap<String, String> row = getOnlyElement(getOnlyElement(rowsCaptor.getAllValues()));
    assertThat(row).containsEntry("registrarId", "SomeRegistrar");
    assertThat(row).containsEntry("registrarName", "Some Registrar");
    assertThat(row).containsEntry("state", "ACTIVE");
    assertThat(row).containsEntry("ianaIdentifier", "8");
    assertThat(row).containsEntry("primaryContacts", "");
    assertThat(row).containsEntry("techContacts", "");
    assertThat(row).containsEntry("marketingContacts", "");
    assertThat(row).containsEntry("abuseContacts", "");
    assertThat(row).containsEntry("whoisInquiryContacts", "");
    assertThat(row).containsEntry("legalContacts", "");
    assertThat(row).containsEntry("billingContacts", "");
    assertThat(row).containsEntry("contactsMarkedAsWhoisAdmin", "");
    assertThat(row).containsEntry("contactsMarkedAsWhoisTech", "");
    assertThat(row).containsEntry("emailAddress", "");
    assertThat(row).containsEntry("address.street", "123 Fake St");
    assertThat(row).containsEntry("address.city", "Fakington");
    assertThat(row).containsEntry("address.state", "");
    assertThat(row).containsEntry("address.zip", "");
    assertThat(row).containsEntry("address.countryCode", "US");
    assertThat(row).containsEntry("phoneNumber", "");
    assertThat(row).containsEntry("faxNumber", "");
    assertThat(row).containsEntry("allowedTlds", "");
    assertThat(row).containsEntry("whoisServer", getDefaultRegistrarWhoisServer());
    assertThat(row).containsEntry("blockPremiumNames", "false");
    assertThat(row).containsEntry("ipAddressAllowList", "");
    assertThat(row).containsEntry("url", "");
    assertThat(row).containsEntry("referralUrl", "");
    assertThat(row).containsEntry("icannReferralEmail", "");
    assertThat(row).containsEntry("billingAccountMap", "{}");
  }
}
