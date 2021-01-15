// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.reporting;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.Iterables;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import java.util.Locale;
import org.joda.time.DateTime;

/** Retrieves {@link HistoryEntry} descendants (e.g. {@link DomainHistory}) from SQL. */
public class HistoryEntryDao {

  public static Iterable<? extends HistoryEntry> loadAllHistoryObjects(
      DateTime afterTime, DateTime beforeTime) {
    jpaTm().assertInTransaction();
    return Iterables.concat(
        loadHistoryObjectsFromTable(ContactHistory.class, beforeTime, afterTime),
        loadHistoryObjectsFromTable(DomainHistory.class, beforeTime, afterTime),
        loadHistoryObjectsFromTable(HostHistory.class, beforeTime, afterTime));
  }

  public static Iterable<? extends HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey, DateTime afterTime, DateTime beforeTime) {
    jpaTm().assertInTransaction();
    // Inspect the parent key to build the table name, result type, and parent-repo-ID field name
    String parentObjectPrefix;
    Class<? extends HistoryEntry> resultClass;
    if (ContactResource.class.isAssignableFrom(parentKey.getKind())) {
      parentObjectPrefix = "Contact";
      resultClass = ContactHistory.class;
    } else if (DomainBase.class.isAssignableFrom(parentKey.getKind())) {
      parentObjectPrefix = "Domain";
      resultClass = DomainHistory.class;
    } else if (HostResource.class.isAssignableFrom(parentKey.getKind())) {
      parentObjectPrefix = "Host";
      resultClass = HostHistory.class;
    } else {
      throw new IllegalArgumentException(
          String.format("Unknown History object parent type %s", parentKey.getKind()));
    }

    String repoIdFieldName =
        String.format("%sRepoId", parentObjectPrefix.toLowerCase(Locale.ENGLISH));
    String tableName = String.format("%sHistory", parentObjectPrefix);
    String queryString =
        String.format(
            "SELECT entry FROM %s entry WHERE entry.modificationTime >= :afterTime AND "
                + "entry.modificationTime <= :beforeTime AND entry.%s = :parentKey",
            tableName, repoIdFieldName);
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery(queryString, resultClass)
                    .setParameter("afterTime", afterTime)
                    .setParameter("beforeTime", beforeTime)
                    .setParameter("parentKey", parentKey.getSqlKey().toString())
                    .getResultList());
  }

  private static Iterable<? extends HistoryEntry> loadHistoryObjectsFromTable(
      Class<? extends HistoryEntry> clazz, DateTime beforeTime, DateTime afterTime) {
    return jpaTm()
        .getEntityManager()
        .createQuery(
            String.format(
                "SELECT entry FROM %s entry WHERE entry.modificationTime >= :afterTime AND "
                    + "entry.modificationTime <= :beforeTime",
                clazz.getSimpleName()),
            clazz)
        .setParameter("afterTime", afterTime)
        .setParameter("beforeTime", beforeTime)
        .getResultList();
  }
}
