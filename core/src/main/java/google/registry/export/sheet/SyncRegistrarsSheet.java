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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.common.Cursor.CursorType.SYNC_REGISTRAR_SHEET;
import static google.registry.model.registrar.RegistrarContact.Type.ABUSE;
import static google.registry.model.registrar.RegistrarContact.Type.ADMIN;
import static google.registry.model.registrar.RegistrarContact.Type.BILLING;
import static google.registry.model.registrar.RegistrarContact.Type.LEGAL;
import static google.registry.model.registrar.RegistrarContact.Type.MARKETING;
import static google.registry.model.registrar.RegistrarContact.Type.TECH;
import static google.registry.model.registrar.RegistrarContact.Type.WHOIS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import google.registry.model.common.Cursor;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.util.Clock;
import google.registry.util.DateTimeUtils;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Class for synchronizing all {@link Registrar} objects to a Google Spreadsheet.
 *
 * @see SyncRegistrarsSheetAction
 */
class SyncRegistrarsSheet {

  @Inject Clock clock;
  @Inject SheetSynchronizer sheetSynchronizer;
  @Inject SyncRegistrarsSheet() {}

  /**
   * Returns true if any {@link Registrar} entity was modified since the last time this task
   * successfully completed, as measured by a cursor.
   */
  boolean wereRegistrarsModified() {
    Optional<Cursor> cursor =
        transactIfJpaTm(
            () -> tm().loadByKeyIfPresent(Cursor.createGlobalVKey(SYNC_REGISTRAR_SHEET)));
    DateTime lastUpdateTime = !cursor.isPresent() ? START_OF_TIME : cursor.get().getCursorTime();
    for (Registrar registrar : Registrar.loadAllCached()) {
      if (DateTimeUtils.isAtOrAfter(registrar.getLastUpdateTime(), lastUpdateTime)) {
        return true;
      }
    }
    return false;
  }

  /** Performs the synchronization operation. */
  void run(String spreadsheetId) throws IOException {
    final DateTime executionTime = clock.nowUtc();
    sheetSynchronizer.synchronize(
        spreadsheetId,
        new Ordering<Registrar>() {
          @Override
          public int compare(Registrar left, Registrar right) {
            return left.getClientId().compareTo(right.getClientId());
          }
        }.immutableSortedCopy(Registrar.loadAllCached()).stream()
            .filter(
                registrar ->
                    registrar.getType() == Registrar.Type.REAL
                        || registrar.getType() == Registrar.Type.OTE)
            .map(
                registrar -> {
                  ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
                  ImmutableSortedSet<RegistrarContact> contacts = registrar.getContacts();
                  RegistrarAddress address =
                      firstNonNull(
                          registrar.getLocalizedAddress(),
                          firstNonNull(
                              registrar.getInternationalizedAddress(),
                              new RegistrarAddress.Builder()
                                  .setStreet(ImmutableList.of("UNKNOWN"))
                                  .setCity("UNKNOWN")
                                  .setCountryCode("US")
                                  .build()));
                  //
                  // （╯°□°）╯ WARNING WARNING WARNING
                  //
                  // Do not change these mappings simply because the Registrar model changed. Only
                  // change these mappings if the people who use the spreadsheet requested it be
                  // changed.
                  //
                  // These values are hard-coded because they correspond to actual spreadsheet
                  // columns. If you change this dictionary, then you'll need to manually add new
                  // columns to the registrar spreadsheets for all environments before deployment,
                  // and you'll need to remove deleted columns probably like a week after
                  // deployment.
                  //
                  builder.put("clientIdentifier", convert(registrar.getClientId()));
                  builder.put("registrarName", convert(registrar.getRegistrarName()));
                  builder.put("state", convert(registrar.getState()));
                  builder.put("ianaIdentifier", convert(registrar.getIanaIdentifier()));
                  builder.put("billingIdentifier", convert(registrar.getBillingIdentifier()));
                  builder.put("billingAccountMap", convert(registrar.getBillingAccountMap()));
                  builder.put("primaryContacts", convertContacts(contacts, byType(ADMIN)));
                  builder.put("techContacts", convertContacts(contacts, byType(TECH)));
                  builder.put("marketingContacts", convertContacts(contacts, byType(MARKETING)));
                  builder.put("abuseContacts", convertContacts(contacts, byType(ABUSE)));
                  builder.put("whoisInquiryContacts", convertContacts(contacts, byType(WHOIS)));
                  builder.put("legalContacts", convertContacts(contacts, byType(LEGAL)));
                  builder.put("billingContacts", convertContacts(contacts, byType(BILLING)));
                  builder.put(
                      "contactsMarkedAsWhoisAdmin",
                      convertContacts(contacts, RegistrarContact::getVisibleInWhoisAsAdmin));
                  builder.put(
                      "contactsMarkedAsWhoisTech",
                      convertContacts(contacts, RegistrarContact::getVisibleInWhoisAsTech));
                  builder.put("emailAddress", convert(registrar.getEmailAddress()));
                  builder.put("address.street", convert(address.getStreet()));
                  builder.put("address.city", convert(address.getCity()));
                  builder.put("address.state", convert(address.getState()));
                  builder.put("address.zip", convert(address.getZip()));
                  builder.put("address.countryCode", convert(address.getCountryCode()));
                  builder.put("phoneNumber", convert(registrar.getPhoneNumber()));
                  builder.put("faxNumber", convert(registrar.getFaxNumber()));
                  builder.put("creationTime", convert(registrar.getCreationTime()));
                  builder.put("lastUpdateTime", convert(registrar.getLastUpdateTime()));
                  builder.put("allowedTlds", convert(registrar.getAllowedTlds()));
                  builder.put("whoisServer", convert(registrar.getWhoisServer()));
                  builder.put("blockPremiumNames", convert(registrar.getBlockPremiumNames()));
                  builder.put("ipAddressAllowList", convert(registrar.getIpAddressAllowList()));
                  builder.put("url", convert(registrar.getUrl()));
                  builder.put("referralUrl", convert(registrar.getUrl()));
                  builder.put("icannReferralEmail", convert(registrar.getIcannReferralEmail()));
                  return builder.build();
                })
            .collect(toImmutableList()));
    tm().transact(() -> tm().put(Cursor.createGlobal(SYNC_REGISTRAR_SHEET, executionTime)));
  }

  private static String convertContacts(
      Iterable<RegistrarContact> contacts, Predicate<RegistrarContact> filter) {
    StringBuilder result = new StringBuilder();
    boolean first = true;
    for (RegistrarContact contact : contacts) {
      if (!filter.test(contact)) {
        continue;
      }
      if (first) {
        first = false;
      } else {
        result.append("\n");
      }
      result.append(contact.toStringMultilinePlainText());
    }
    return result.toString();
  }

  private static Predicate<RegistrarContact> byType(final RegistrarContact.Type type) {
    return contact -> contact.getTypes().contains(type);
  }

  /** Converts a value to a string representation that can be stored in a spreadsheet cell. */
  private static String convert(@Nullable Object value) {
    if (value == null) {
      return "";
    } else if (value instanceof Iterable) {
      return Joiner.on('\n').join((Iterable<?>) value);
    } else {
      return value.toString();
    }
  }
}
