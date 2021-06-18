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

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import java.io.Serializable;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;

/** Interface for query instances used by {@link RegistryJpaIO.Read}. */
public interface RegistryQuery<T> extends Serializable {
  Stream<T> stream();

  interface CriteriaQuerySupplier<T> extends Supplier<CriteriaQuery<T>>, Serializable {}

  /**
   * Returns a {@link RegistryQuery} that creates a string query from constant text.
   *
   * @param nativeQuery whether the given string is to be interpreted as a native query or JPQL.
   * @param parameters parameters to be substituted in the query.
   * @param <T> Type of each row in the result set, {@link Object} in single-select queries, and
   *     {@code Object[]} in multi-select queries.
   */
  static <T> RegistryQuery<T> createQuery(
      String sql, @Nullable Map<String, Object> parameters, boolean nativeQuery) {
    return () -> {
      EntityManager entityManager = jpaTm().getEntityManager();
      Query query =
          nativeQuery ? entityManager.createNativeQuery(sql) : entityManager.createQuery(sql);
      if (parameters != null) {
        parameters.forEach(query::setParameter);
      }
      @SuppressWarnings("unchecked")
      Stream<T> resultStream = query.getResultStream();
      return nativeQuery ? resultStream : resultStream.map(e -> detach(entityManager, e));
    };
  }

  /**
   * Returns a {@link RegistryQuery} that creates a typed JPQL query from constant text.
   *
   * @param parameters parameters to be substituted in the query.
   * @param <T> Type of each row in the result set.
   */
  static <T> RegistryQuery<T> createQuery(
      String jpql, @Nullable Map<String, Object> parameters, Class<T> clazz) {
    return () -> {
      TypedQuery<T> query = jpaTm().query(jpql, clazz);
      if (parameters != null) {
        parameters.forEach(query::setParameter);
      }
      return query.getResultStream();
    };
  }

  /**
   * Returns a {@link RegistryQuery} from a {@link CriteriaQuery} supplier.
   *
   * <p>A serializable supplier is needed in because {@link CriteriaQuery} itself must be created
   * within a transaction, and we are not in a transaction yet when this function is called to set
   * up the pipeline.
   *
   * @param <T> Type of each row in the result set.
   */
  static <T> RegistryQuery<T> createQuery(CriteriaQuerySupplier<T> criteriaQuery) {
    return () -> jpaTm().query(criteriaQuery.get()).getResultStream();
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
    try {
      entityManager.detach(object);
    } catch (IllegalArgumentException e) {
      // Not an entity. Do nothing.
    }
  }
}
