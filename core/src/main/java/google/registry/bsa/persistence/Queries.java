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
import static google.registry.bsa.BsaStringUtils.DOMAIN_SPLITTER;
import static google.registry.bsa.BsaTransactions.bsaQuery;
import static google.registry.persistence.transaction.JpaTransactionManagerImpl.em;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.CreateAutoTimestamp;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/** Helpers for querying BSA JPA entities. */
public final class Queries {

  private Queries() {}

  /**
   * Entity objects that may be updated in the same query must be detached. See {@code
   * JpaTransactionManagerImpl}.
   */
  private static Object detach(Object obj) {
    em().detach(obj);
    return obj;
  }

  public static ImmutableList<String> batchReadBsaLabelText(
      Optional<String> lastRead, int batchSize) {

    return ImmutableList.copyOf(
        bsaQuery(
            () ->
                em().createQuery(
                        "SELECT b.label FROM BsaLabel b WHERE b.label > :lastRead ORDER BY b.label",
                        String.class)
                    .setParameter("lastRead", lastRead.orElse(""))
                    .setMaxResults(batchSize)
                    .getResultList()));
  }

  static Stream<BsaUnblockableDomain> queryBsaUnblockableDomainByLabels(
      ImmutableCollection<String> labels) {
    return ((Stream<?>)
            em().createQuery("FROM BsaUnblockableDomain WHERE label in (:labels)")
                .setParameter("labels", labels)
                .getResultStream())
        .map(Queries::detach)
        .map(BsaUnblockableDomain.class::cast);
  }

  static Stream<BsaLabel> queryBsaLabelByLabels(ImmutableCollection<String> labels) {
    return ((Stream<?>)
            em().createQuery("FROM BsaLabel where label in (:labels)")
                .setParameter("labels", labels)
                .getResultStream())
        .map(Queries::detach)
        .map(BsaLabel.class::cast);
  }

  static int deleteBsaLabelByLabels(ImmutableCollection<String> labels) {
    return em().createQuery("DELETE FROM BsaLabel where label IN (:deleted_labels)")
        .setParameter("deleted_labels", labels)
        .executeUpdate();
  }

  static ImmutableList<BsaUnblockableDomain> batchReadUnblockables(
      Optional<BsaUnblockableDomain> lastRead, int batchSize) {
    return ImmutableList.copyOf(
        bsaQuery(
            () ->
                em().createQuery(
                        "FROM BsaUnblockableDomain d WHERE d.label > :label OR (d.label = :label"
                            + " AND d.tld >  :tld) ORDER BY d.tld, d.label ")
                    .setParameter("label", lastRead.map(d -> d.label).orElse(""))
                    .setParameter("tld", lastRead.map(d -> d.tld).orElse(""))
                    .setMaxResults(batchSize)
                    .getResultList()));
  }

  static ImmutableSet<String> queryUnblockablesByNames(ImmutableSet<String> domains) {
    String labelTldParis =
        domains.stream()
            .map(
                domain -> {
                  List<String> parts = DOMAIN_SPLITTER.splitToList(domain);
                  verify(parts.size() == 2, "Invalid domain name %s", domain);
                  return String.format("('%s','%s')", parts.get(0), parts.get(1));
                })
            .collect(Collectors.joining(","));
    String sql =
        String.format(
            "SELECT CONCAT(d.label, '.', d.tld) FROM \"BsaUnblockableDomain\" d "
                + "WHERE (d.label, d.tld) IN (%s)",
            labelTldParis);
    return ImmutableSet.copyOf(em().createNativeQuery(sql).getResultList());
  }

  static ImmutableSet<String> queryNewlyCreatedDomains(
      ImmutableCollection<String> tlds, DateTime minCreationTime, DateTime now) {
    return ImmutableSet.copyOf(
        em().createQuery(
                "SELECT domainName FROM Domain WHERE creationTime >= :minCreationTime "
                    + "AND deletionTime > :now "
                    + "AND tld in (:tlds)",
                String.class)
            .setParameter("minCreationTime", CreateAutoTimestamp.create(minCreationTime))
            .setParameter("now", now)
            .setParameter("tlds", tlds)
            .getResultList());
  }
}
