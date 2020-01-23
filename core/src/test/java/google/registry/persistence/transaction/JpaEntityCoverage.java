// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.transaction;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import google.registry.persistence.PersistenceXmlUtility;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.Entity;
import org.junit.rules.ExternalResource;

/* Checks for JPA {@link Entity entities} that are declared in persistence.xml
 * but not persisted in integration tests.
 */
public class JpaEntityCoverage extends ExternalResource {

  // TODO(weiminyu): remove this set after pr/438 is submitted. The pr is expected to fix the
  // problems with these entities and allow them to be tested.
  private static final ImmutableSet<String> IGNORE_ENTITIES =
      ImmutableSet.of(
          "DomainBase",
          "BaseTransferObject",
          "DelegationSignerData",
          "DesignatedContact",
          "GracePeriod");

  private static final ImmutableSet<Class> ALL_JPA_ENTITIES =
      PersistenceXmlUtility.getManagedClasses().stream()
          .filter(e -> !IGNORE_ENTITIES.contains(e.getSimpleName()))
          .filter(e -> e.getAnnotation(Entity.class) != null)
          .collect(ImmutableSet.toImmutableSet());
  private static final Set<Class> allCoveredJpaEntities = Sets.newHashSet();
  // Map of test class name to boolean flag indicating if it tests any JPA entities.
  private static final Map<String, Boolean> testClassRelevance = Maps.newHashMap();

  // Provides class name of the test being executed.
  private final TestCaseWatcher watcher;

  public JpaEntityCoverage(TestCaseWatcher watcher) {
    this.watcher = watcher;
  }

  @Override
  public void before() {
    testClassRelevance.putIfAbsent(watcher.getTestClass(), false);
  }

  @Override
  public void after() {
    ALL_JPA_ENTITIES.stream()
        .filter(JpaEntityCoverage::isPersisted)
        .forEach(
            entity -> {
              allCoveredJpaEntities.add(entity);
              testClassRelevance.put(watcher.getTestClass(), true);
            });
  }

  public static void init() {
    allCoveredJpaEntities.clear();
    testClassRelevance.clear();
  }

  public static Set<Class> getUncoveredEntities() {
    return Sets.difference(ALL_JPA_ENTITIES, allCoveredJpaEntities);
  }

  public static ImmutableList<String> getIrrelevantTestClasses() {
    return testClassRelevance.entrySet().stream()
        .filter(e -> !e.getValue())
        .map(Map.Entry::getKey)
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Checks if at least one instance of {@code entityType} has been persisted during the test, and
   * if yes, reads one of them back. If the read is successful, there is no incompatibility between
   * server and schema regarding type.
   *
   * @return true if an instance of {@code entityType} is found in the database and can be read
   */
  private static boolean isPersisted(Class entityType) {
    List result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createQuery(
                            String.format("SELECT e FROM %s e", entityType.getSimpleName()),
                            entityType)
                        .setMaxResults(1)
                        .getResultList());
    return !result.isEmpty() && entityType.isInstance(result.get(0));
  }
}
