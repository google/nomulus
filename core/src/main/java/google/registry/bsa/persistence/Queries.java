// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa.persistence;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.bsa.BsaStringUtils.DOMAIN_SPLITTER;
import static google.registry.bsa.BsaTransactions.bsaQuery;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.bsa.api.UnblockableDomain;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Helpers for querying BSA JPA entities. */
public final class Queries {

  private Queries() {}

  /**
   * Entity objects that may be updated in the same query must be detached. See {@code
   * JpaTransactionManagerImpl}.
   */
  private static Object detach(Object obj) {
    tm().getEntityManager().detach(obj);
    return obj;
  }

  public static ImmutableList<String> batchReadBsaLabelText(
      Optional<String> lastRead, int batchSize) {
    return ImmutableList.copyOf(
        bsaQuery(
            () ->
                tm().getEntityManager()
                    .createQuery(
                        "SELECT b.label FROM BsaLabel b WHERE b.label > :lastRead ORDER BY b.label",
                        String.class)
                    .setParameter("lastRead", lastRead.orElse(""))
                    .setMaxResults(batchSize)
                    .getResultList()));
  }

  public static ImmutableList<UnblockableDomain> batchReadUnblockableDomains(
      Optional<UnblockableDomain> lastRead, int batchSize) {
    return batchReadUnblockables(lastRead.map(BsaUnblockableDomain::of), batchSize).stream()
        .map(BsaUnblockableDomain::toUnblockableDomain)
        .collect(toImmutableList());
  }

  public static Stream<UnblockableDomain> queryUnblockableDomainByLabels(
      ImmutableCollection<String> labels) {
    return queryBsaUnblockableDomainByLabels(labels).map(BsaUnblockableDomain::toUnblockableDomain);
  }

  static Stream<BsaUnblockableDomain> queryBsaUnblockableDomainByLabels(
      ImmutableCollection<String> labels) {
    return ((Stream<?>)
            tm().getEntityManager()
                .createQuery("FROM BsaUnblockableDomain WHERE label in (:labels)")
                .setParameter("labels", labels)
                .getResultStream())
        .map(Queries::detach)
        .map(BsaUnblockableDomain.class::cast);
  }

  static Stream<BsaLabel> queryBsaLabelByLabels(ImmutableCollection<String> labels) {
    return ((Stream<?>)
            tm().getEntityManager()
                .createQuery("FROM BsaLabel where label in (:labels)")
                .setParameter("labels", labels)
                .getResultStream())
        .map(Queries::detach)
        .map(BsaLabel.class::cast);
  }

  static int deleteBsaLabelByLabels(ImmutableCollection<String> labels) {
    return tm().getEntityManager()
        .createQuery("DELETE FROM BsaLabel where label IN (:deleted_labels)")
        .setParameter("deleted_labels", labels)
        .executeUpdate();
  }

  static ImmutableList<BsaUnblockableDomain> batchReadUnblockables(
      Optional<BsaUnblockableDomain> lastRead, int batchSize) {
    return ImmutableList.<BsaUnblockableDomain>copyOf(
        bsaQuery(
            () ->
                tm().getEntityManager()
                    .createQuery(
                        "FROM BsaUnblockableDomain d WHERE d.label > :label OR (d.label = :label"
                            + " AND d.tld >  :tld) ORDER BY d.label, d.tld ")
                    .setParameter("label", lastRead.map(d -> d.label).orElse(""))
                    .setParameter("tld", lastRead.map(d -> d.tld).orElse(""))
                    .setMaxResults(batchSize)
                    .getResultList()));
  }

  static ImmutableSet<String> queryUnblockablesByNames(ImmutableSet<String> domains) {
    if (domains.isEmpty()) {
      return ImmutableSet.of();
    }
    ImmutableList<String> domainList = ImmutableList.copyOf(domains);
    StringBuilder sqlBuilder =
        new StringBuilder(
            "SELECT CONCAT(d.label, '.', d.tld) FROM \"BsaUnblockableDomain\" d WHERE (d.label,"
                + " d.tld) IN (");
    for (int i = 0; i < domainList.size(); i++) {
      if (i > 0) {
        sqlBuilder.append(",");
      }
      sqlBuilder.append("(:label").append(i).append(", :tld").append(i).append(")");
    }
    sqlBuilder.append(")");

    var query = tm().getEntityManager().createNativeQuery(sqlBuilder.toString());
    for (int i = 0; i < domainList.size(); i++) {
      List<String> parts = DOMAIN_SPLITTER.splitToList(domainList.get(i));
      verify(parts.size() == 2, "Invalid domain name %s", domainList.get(i));
      query.setParameter("label" + i, parts.get(0));
      query.setParameter("tld" + i, parts.get(1));
    }

    @SuppressWarnings("unchecked")
    List<String> resultList = (List<String>) query.getResultList();
    return ImmutableSet.copyOf(resultList);
  }

  static ImmutableSet<String> queryNewlyCreatedDomains(
      ImmutableCollection<String> tlds, Instant minCreationTime, Instant now) {
    return ImmutableSet.copyOf(
        tm().getEntityManager()
            .createQuery(
                "SELECT domainName FROM Domain WHERE creationTime.creationTime >= :minCreationTime "
                    + "AND deletionTime > :now "
                    + "AND tld in (:tlds)",
                String.class)
            .setParameter("minCreationTime", minCreationTime)
            .setParameter("now", now)
            .setParameter("tlds", tlds)
            .getResultList());
  }

  /**
   * Finds all currently registered domains that match BSA labels but are not recorded as
   * unblockable.
   *
   * @return The missing unblockables and their creation and deletion time.
   */
  public static ImmutableList<DomainLifeSpan> queryMissedRegisteredUnblockables(
      String tld, Instant now) {
    String sqlTemplate =
        """
        SELECT l.domain_name, creation_time, deletion_time
        FROM
            (SELECT d.domain_name, d.creation_time, d.deletion_time
             FROM
                 "Domain" d
             JOIN
                 (SELECT concat(label, '.', :tld) AS domain_name from "BsaLabel") b
             ON b.domain_name = d.domain_name
             WHERE deletion_time > :now) l
        LEFT OUTER JOIN
            (SELECT concat(label, '.', tld) as domain_name
             FROM "BsaUnblockableDomain"
             WHERE tld = :tld and reason = 'REGISTERED') r
        ON l.domain_name = r.domain_name
        WHERE r.domain_name is null;
        """;

    return ((Stream<?>)
            tm().getEntityManager()
                .createNativeQuery(sqlTemplate)
                .setParameter("tld", tld)
                .setParameter("now", now)
                .getResultStream())
        .map(Object[].class::cast)
        .map(row -> new DomainLifeSpan((String) row[0], (Instant) row[1], (Instant) row[2]))
        .collect(toImmutableList());
  }

  public record DomainLifeSpan(String domainName, Instant creationTime, Instant deletionTime) {}
}
