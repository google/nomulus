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

package google.registry.bsa;

import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.ReservationType;
import google.registry.model.tld.label.ReservedList;
import google.registry.model.tld.label.ReservedList.ReservedListEntry;
import google.registry.model.tld.label.ReservedListDao;

/** Helpers for setting up reserved lists in tests. */
public final class ReservedDomainsTestingUtils {

  private ReservedDomainsTestingUtils() {}

  public static void createReservedList(
      String listName, ImmutableMap<String, ReservationType> reservedLabels) {
    ImmutableMap<String, ReservedListEntry> entries =
        ImmutableMap.copyOf(
            Maps.transformEntries(
                reservedLabels, (key, value) -> ReservedListEntry.create(key, value, "")));

    ReservedListDao.save(
        new ReservedList.Builder()
            .setName(listName)
            .setCreationTimestamp(START_OF_TIME)
            .setReservedListMap(entries)
            .build());
  }

  public static void createReservedList(
      String listName, String label, ReservationType reservationType) {
    createReservedList(listName, ImmutableMap.of(label, reservationType));
  }

  public static void addReservedListsToTld(String tldStr, ImmutableList<String> listNames) {
    Tld tld = Tld.get(tldStr);
    ImmutableSet<String> reservedLists =
        new ImmutableSet.Builder<String>()
            .addAll(tld.getReservedListNames())
            .addAll(listNames)
            .build();
    persistResource(tld.asBuilder().setReservedListsByName(reservedLists).build());
  }

  public static void addReservedDomainToList(
      String listName, ImmutableMap<String, ReservationType> reservedLabels) {
    ImmutableMap<String, ReservedListEntry> existingEntries =
        ReservedList.get(listName).get().getReservedListEntries();
    ImmutableMap<String, ReservedListEntry> newEntries =
        ImmutableMap.copyOf(
            Maps.transformEntries(
                reservedLabels, (key, value) -> ReservedListEntry.create(key, value, "")));

    ReservedListDao.save(
        new ReservedList.Builder()
            .setName(listName)
            .setCreationTimestamp(START_OF_TIME)
            .setReservedListMap(
                new ImmutableMap.Builder<String, ReservedListEntry>()
                    .putAll(existingEntries)
                    .putAll(newEntries)
                    .buildKeepingLast())
            .build());
  }

  public static void removeReservedDomainFromList(
      String listName, ImmutableSet<String> removedLabels) {
    ImmutableMap<String, ReservedListEntry> existingEntries =
        ReservedList.get(listName).get().getReservedListEntries();
    ImmutableMap<String, ReservedListEntry> newEntries =
        ImmutableMap.copyOf(
            Maps.filterEntries(existingEntries, entry -> !removedLabels.contains(entry.getKey())));

    ReservedListDao.save(
        new ReservedList.Builder()
            .setName(listName)
            .setCreationTimestamp(START_OF_TIME)
            .setReservedListMap(newEntries)
            .build());
  }
}
