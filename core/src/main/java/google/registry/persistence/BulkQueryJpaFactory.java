// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import google.registry.model.bulkquery.BulkQueryEntities;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.JpaTransactionManagerImpl;
import google.registry.util.Clock;
import java.util.List;
import javax.persistence.EntityManagerFactory;
import org.hibernate.jpa.boot.internal.ParsedPersistenceXmlDescriptor;
import org.hibernate.jpa.boot.spi.Bootstrap;

/**
 * Defines factory method for instantiating the bulk-query optimized {@link JpaTransactionManager}.
 */
public final class BulkQueryJpaFactory {

  private BulkQueryJpaFactory() {}

  static EntityManagerFactory createBulkQueryEntityManagerFactory(
      ImmutableMap<String, String> cloudSqlConfigs) {
    ParsedPersistenceXmlDescriptor descriptor =
        PersistenceXmlUtility.getParsedPersistenceXmlDescriptor();

    List<String> updatedManagedClasses =
        Streams.concat(
                descriptor.getManagedClassNames().stream(),
                BulkQueryEntities.JPA_ENTITIES_NEW.stream())
            .map(
                name -> {
                  if (BulkQueryEntities.JPA_ENTITIES_REPLACEMENTS.containsKey(name)) {
                    return BulkQueryEntities.JPA_ENTITIES_REPLACEMENTS.get(name);
                  }
                  return name;
                })
            .collect(ImmutableList.toImmutableList());

    descriptor.getManagedClassNames().clear();
    descriptor.getManagedClassNames().addAll(updatedManagedClasses);

    return Bootstrap.getEntityManagerFactoryBuilder(descriptor, cloudSqlConfigs).build();
  }

  public static JpaTransactionManager createBulkQueryJpaTransactionManager(
      ImmutableMap<String, String> cloudSqlConfigs, Clock clock) {
    return new JpaTransactionManagerImpl(
        createBulkQueryEntityManagerFactory(cloudSqlConfigs), clock);
  }
}
