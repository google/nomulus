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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumList.PremiumEntry;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.model.tld.label.PremiumListUtils;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/** Command to safely update {@link PremiumList} in Database for a given TLD. */
@Parameters(separators = " =", commandDescription = "Update a PremiumList in Database.")
class UpdatePremiumListCommand extends CreateOrUpdatePremiumListCommand {

  @Override
  // Using UpdatePremiumListAction.java as reference;
  protected String prompt() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(inputFile) : name;
    Optional<PremiumList> list = PremiumListDao.getLatestRevision(name);
    checkArgument(
        list.isPresent(),
        String.format("Could not update premium list %s because it doesn't exist.", name));
    List<String> existingEntry = getExistingPremiumEntry(list.get()).asList();
    inputData = Files.readAllLines(inputFile, UTF_8);
    currency = list.get().getCurrency();
    // reconstructing existing premium list to bypass Hibernate lazy initialization exception
    PremiumList existingPremiumList =
        PremiumListUtils.parseToPremiumList(name, currency, existingEntry);
    PremiumList updatedPremiumList = PremiumListUtils.parseToPremiumList(name, currency, inputData);

    return String.format(
        "Update premium list for %s?\n Old List: %s\n New List: %s",
        name, existingPremiumList, updatedPremiumList);
  }

  /*
    To get premium list content as a set of string. This is a workaround to avoid dealing with
    Hibernate.LazyInitizationException error. It occurs when trying to access data of the
    latest revision of an existing premium list.
    "Cannot evaluate google.registry.model.tld.label.PremiumList.toString()'".
    Ideally, the following should be the way to verify info in latest revision of a premium list:

    PremiumList existingPremiumList =
        PremiumListSqlDao.getLatestRevision(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "Could not update premium list %s because it doesn't exist.", name)));
    assertThat(persistedList.getLabelsToPrices()).containsEntry("foo", new BigDecimal("9000.00"));
    assertThat(persistedList.size()).isEqualTo(1);
  */
  protected ImmutableSet<String> getExistingPremiumEntry(PremiumList list) {

    Iterable<PremiumEntry> sqlListEntries =
        jpaTm().transact(() -> PremiumListDao.loadPremiumEntries(list));
    return Streams.stream(sqlListEntries)
        .map(
            premiumEntry ->
                String.format(
                    "%s,%s %s",
                    premiumEntry.getDomainLabel(), list.getCurrency(), premiumEntry.getValue()))
        .collect(toImmutableSet());
  }
}
