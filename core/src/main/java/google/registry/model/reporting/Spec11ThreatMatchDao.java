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

import com.google.common.collect.ImmutableList;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.util.DateTimeUtils;
import javax.persistence.TemporalType;
import org.joda.time.LocalDate;

/**
 * Data access object for {@link Spec11ThreatMatch}.
 *
 * <p>The transaction manager is passed as a parameter because this could be called either from a
 * BEAM pipeline or standard non-BEAM code.
 */
public class Spec11ThreatMatchDao {

  /** Delete all entries with the specified date from the database. */
  public static void deleteEntriesByDate(JpaTransactionManager jpaTm, LocalDate date) {
    jpaTm.assertInTransaction();
    jpaTm
        .getEntityManager()
        .createQuery("DELETE FROM Spec11ThreatMatch WHERE check_date = :date")
        .setParameter("date", DateTimeUtils.toSqlDate(date), TemporalType.DATE)
        .executeUpdate();
  }

  /** Query the database and return a list of domain names with the specified date. */
  public static ImmutableList<Spec11ThreatMatch> loadEntriesByDate(
      JpaTransactionManager jpaTm, LocalDate date) {
    jpaTm.assertInTransaction();
    return ImmutableList.copyOf(
        jpaTm
            .getEntityManager()
            .createQuery(
                "SELECT match FROM Spec11ThreatMatch match WHERE match.checkDate = :date",
                Spec11ThreatMatch.class)
            .setParameter("date", date)
            .getResultList());
  }
}
