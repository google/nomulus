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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableCollection;
import java.util.stream.Stream;

/** Helpers for querying BSA JPA entities. */
class Queries {

  private Queries() {}

  static Stream<BsaDomainInUse> queryBsaDomainInUseByLabels(ImmutableCollection<String> labels) {
    return ((Stream<?>)
            tm().getEntityManager()
                .createQuery("FROM BsaDomainInUse WHERE label in (:labels)")
                .setParameter("labels", labels)
                .getResultStream())
        .map(Queries::detach)
        .map(BsaDomainInUse.class::cast);
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

  static Object detach(Object obj) {
    tm().getEntityManager().detach(obj);
    return obj;
  }
}
