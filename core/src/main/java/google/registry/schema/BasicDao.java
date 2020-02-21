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
import google.registry.persistence.VKey;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Optional;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;

/** Basic data access object which provides common CRUD methods for entities. */
public class BasicDao {

  /** Persists a new entity in Cloud SQL, throws exception if the entity already exists. */
  public void saveNew(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
  }

  /** Persists all new entities in Cloud SQL, throws exception if any entity already exists. */
  public void saveAllNew(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    jpaTm().transact(() -> entities.forEach(this::saveNew));
  }

  /** Persists a new entity or update the existing entity in Cloud SQL. */
  public void merge(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    jpaTm().transact(() -> jpaTm().getEntityManager().merge(entity));
  }

  /** Persists all new entities or update the existing entities in Cloud SQL. */
  public void mergeAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    jpaTm().transact(() -> entities.forEach(this::merge));
  }

  /** Updates an entity in Cloud SQL, throws exception if the entity does not exist. */
  public void update(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    jpaTm()
        .transact(
            () -> {
              checkArgument(checkExists(entity), "Given entity does not exist");
              jpaTm().getEntityManager().merge(entity);
            });
  }

  /** Updates all entities in Cloud SQL, throws exception if any entity does not exist. */
  public void updateAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    jpaTm().transact(() -> entities.forEach(this::update));
  }

  /** Returns whether the given entity exists. */
  public boolean checkExists(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    EntityType<?> entityType = getEntityType(entity.getClass());
    ImmutableSet<String> entityIdFieldNames = getEntityIdFieldNames(entityType);
    return jpaTm()
        .transact(
            () -> {
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
  public <T> Optional<T> load(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    return Optional.ofNullable(
        jpaTm().transact(() -> jpaTm().getEntityManager().find(key.getKind(), key.getSqlKey())));
  }

  /** Loads all entities of the given type, returns empty if there is no such entity. */
  public <T> ImmutableList<T> loadAll(Class<T> clazz) {
    checkArgumentNotNull(clazz, "clazz must be specified");
    return ImmutableList.copyOf(
        jpaTm()
            .transact(
                () -> {
                  EntityType<T> entityType =
                      jpaTm().getEntityManager().getMetamodel().entity(clazz);
                  return jpaTm()
                      .getEntityManager()
                      .createQuery(
                          String.format("SELECT entity FROM %s entity", entityType.getName()),
                          clazz)
                      .getResultList();
                }));
  }

  /** Deletes the entity by its id, returns the number of deleted entity. */
  public <T> int delete(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    EntityType<?> entityType = getEntityType(key.getKind());
    ImmutableSet<String> entityIdFieldNames = getEntityIdFieldNames(entityType);
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
                        entityType.hasSingleIdAttribute()
                            ? key.getSqlKey()
                            : getFieldValue(key.getSqlKey(), idFieldName);
                    query.setParameter(idFieldName, idFieldValue);
                  });
              return query.executeUpdate();
            });
  }

  /** Deletes the entity by its id, throws exception if the entity is not deleted. */
  public <T> void assertDelete(VKey<T> key) {
    jpaTm()
        .transact(
            () -> {
              if (delete(key) != 1) {
                throw new IllegalArgumentException(
                    String.format("Error deleting the entity of the key: %s", key.getSqlKey()));
              }
            });
  }

  private String getAndClause(Collection<String> fieldNames) {
    return fieldNames.stream()
        .map(idName -> String.format("%s = :%s", idName, idName))
        .collect(joining(" AND "));
  }

  private static <T> EntityType<T> getEntityType(Class<T> clazz) {
    return jpaTm().getEntityManagerFactory().getMetamodel().entity(clazz);
  }

  private static ImmutableSet<String> getEntityIdFieldNames(EntityType<?> entityType) {
    return entityType.hasSingleIdAttribute()
        ? ImmutableSet.of(entityType.getDeclaredId(entityType.getIdType().getJavaType()).getName())
        : entityType.getIdClassAttributes().stream()
            .map(SingularAttribute::getName)
            .collect(toImmutableSet());
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
