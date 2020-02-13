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

package google.registry.schema;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Optional;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;

/** Basic data access object which provides common CRUD methods for entities. */
public class BasicDao<T> {

  private final Class<T> entityClass;
  private final EntityType<T> entityType;
  private final ImmutableSet<String> entityIdFieldNames;

  protected BasicDao() {
    entityClass = new TypeInstantiator<T>(getClass()) {}.getExactType();
    entityType = jpaTm().getEntityManagerFactory().getMetamodel().entity(entityClass);
    entityIdFieldNames =
        entityType.hasSingleIdAttribute()
            ? ImmutableSet.of(
                entityType.getDeclaredId(entityType.getIdType().getJavaType()).getName())
            : entityType.getIdClassAttributes().stream()
                .map(SingularAttribute::getName)
                .collect(toImmutableSet());
  }

  /** Persists a new entity in Cloud SQL, throws exception if the entity already exists. */
  public void saveNew(T entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
  }

  /** Persists all new entities in Cloud SQL, throws exception if any entity already exists. */
  public void saveAllNew(ImmutableCollection<T> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    jpaTm().transact(() -> entities.forEach(this::saveNew));
  }

  /** Persists a new entity or update the existing entity in Cloud SQL. */
  public void merge(T entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    jpaTm().transact(() -> jpaTm().getEntityManager().merge(entity));
  }

  /** Persists all new entities or update the existing entities in Cloud SQL. */
  public void mergeAll(ImmutableCollection<T> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    jpaTm().transact(() -> entities.forEach(this::merge));
  }

  /** Updates an entity in Cloud SQL, throws exception if the entity does not exist. */
  public void update(T entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    jpaTm()
        .transact(
            () -> {
              checkArgument(checkExists(entity), "Given entity does not exist");
              jpaTm().getEntityManager().merge(entity);
            });
  }

  /** Updates all entities in Cloud SQL, throws exception if any entity does not exist. */
  public void updateAll(ImmutableCollection<T> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    jpaTm().transact(() -> entities.forEach(this::update));
  }

  /** Returns whether the given entity exists. */
  public boolean checkExists(T entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    return jpaTm()
        .transact(
            () -> {
              EntityType<T> entityType =
                  jpaTm().getEntityManager().getMetamodel().entity(entityClass);
              TypedQuery<Integer> query =
                  jpaTm()
                      .getEntityManager()
                      .createQuery(
                          String.format(
                              "SELECT 1 FROM %s WHERE %s",
                              entityType.getName(), getAndClause(entityIdFieldNames)),
                          Integer.class)
                      .setMaxResults(1);
              entityIdFieldNames.forEach(
                  idFieldName ->
                      query.setParameter(idFieldName, getFieldValue(entity, idFieldName)));
              return query.getResultList().size() > 0;
            });
  }

  /** Loads the entity by its id, returns empty if the entity doesn't exist. */
  public Optional<T> load(Object id) {
    checkArgumentNotNull(id, "id must be specified");
    return Optional.ofNullable(
        jpaTm().transact(() -> jpaTm().getEntityManager().find(entityClass, id)));
  }

  /** Loads all entities of the given type, returns empty if there is no such entity. */
  public ImmutableList<T> loadAll() {
    return ImmutableList.copyOf(
        jpaTm()
            .transact(
                () -> {
                  EntityType<T> entityType =
                      jpaTm().getEntityManager().getMetamodel().entity(entityClass);
                  return jpaTm()
                      .getEntityManager()
                      .createQuery(
                          String.format("SELECT entity FROM %s entity", entityType.getName()),
                          entityClass)
                      .getResultList();
                }));
  }

  /** Deletes the entity by its id, returns the number of deleted entity. */
  public int delete(Object id) {
    checkArgumentNotNull(id, "id must be specified");
    return jpaTm()
        .transact(
            () -> {
              String sql =
                  String.format(
                      "DELETE FROM %s WHERE %s",
                      entityType.getName(), getAndClause(entityIdFieldNames));
              Query query = jpaTm().getEntityManager().createQuery(sql);
              entityIdFieldNames.forEach(
                  idFieldName -> {
                    Object idFieldValue =
                        entityType.hasSingleIdAttribute() ? id : getFieldValue(id, idFieldName);
                    query.setParameter(idFieldName, idFieldValue);
                  });
              return query.executeUpdate();
            });
  }

  /** Deletes the entity by its id, throws exception if the entity is not deleted. */
  public void assertDelete(Object id) {
    jpaTm()
        .transact(
            () -> {
              if (delete(id) != 1) {
                throw new IllegalArgumentException(
                    String.format("Error deleting the entity of the id: %s", id));
              }
            });
  }

  private String getAndClause(Collection<String> fieldNames) {
    return fieldNames.stream()
        .map(idName -> String.format("%s = :%s", idName, idName))
        .collect(joining(" AND "));
  }

  private static Object getFieldValue(Object object, String fieldName) {
    try {
      Field field = object.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(object);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
