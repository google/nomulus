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

package google.registry.beam.common;

import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.QueryComposer;
import java.util.stream.Stream;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.metamodel.Metamodel;
import org.apache.beam.sdk.transforms.SerializableFunction;

/** Interface for query instances used by {@link RegistryJpaIO.Read}. */
public interface RegistryQuery<T> {
  Stream<T> stream();

  /** Factory for {@link RegistryQuery}. */
  interface RegistryQueryFactory<T>
      extends SerializableFunction<JpaTransactionManager, RegistryQuery<T>> {}

  // TODO(mmuller): Consider detached JpaQueryComposer that works with any JpaTransactionManager
  // instance, i.e., change composer.buildQuery() to composer.buildQuery(JpaTransactionManager).
  // This way QueryComposer becomes reusable and serializable (at least with Hibernate), and this
  // interface would no longer be necessary.
  interface QueryComposerFactory<T>
      extends SerializableFunction<JpaTransactionManager, QueryComposer<T>> {}

  /**
   * Returns a {@link RegistryQueryFactory} that creates a JPQL query from constant text.
   *
   * @param <T> Type of each row in the result set, {@link Object} in single-select queries, and
   *     {@code Object[]} in multi-select queries.
   */
  @SuppressWarnings("unchecked") // query.getResultStream: jpa api uses raw type
  static <T> RegistryQueryFactory<T> createQueryFactory(String jpql) {
    return (JpaTransactionManager jpa) ->
        () -> {
          EntityManager entityManager = jpa.getEntityManager();
          Query query = entityManager.createQuery(jpql);
          return query.getResultStream().map(e -> detach(entityManager, e));
        };
  }

  static <T> RegistryQueryFactory<T> createQueryFactory(
      QueryComposerFactory<T> queryComposerFactory) {
    return (JpaTransactionManager jpa) ->
        () -> queryComposerFactory.apply(jpa).withAutoDetachOnLoad(true).stream();
  }

  /**
   * Removes an object from the JPA session cache if applicable.
   *
   * @param object An object that represents a row in the result set. It may be a JPA entity, a
   *     non-entity object, or an array that holds JPA entities and/or non-entities.
   */
  static <T> T detach(EntityManager entityManager, T object) {
    if (object.getClass().isArray()) {
      for (Object arrayElement : (Object[]) object) {
        detachObject(entityManager, arrayElement);
      }
    } else {
      detachObject(entityManager, object);
    }
    return object;
  }

  static void detachObject(EntityManager entityManager, Object object) {
    Class<?> objectClass = object.getClass();
    if (objectClass.isPrimitive() || objectClass == String.class) {
      return;
    }
    Metamodel metamodel = entityManager.getMetamodel();
    try {
      metamodel.entity(objectClass);
      entityManager.detach(object);
    } catch (IllegalArgumentException e) {
      // Not an entity. Do nothing.
    }
  }
}
