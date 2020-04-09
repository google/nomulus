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

package google.registry.persistence;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import javax.persistence.Embeddable;
import javax.persistence.Embedded;
import javax.persistence.MappedSuperclass;
import javax.persistence.PostLoad;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;
import javax.persistence.PrePersist;
import javax.persistence.PreRemove;
import javax.persistence.PreUpdate;

/**
 * A listener class to invoke entity callbacks in cases where Hibernate doesn't invoke the callback
 * as expected.
 *
 * <p>JPA defines a few annotations, e.g. {@link PostLoad}, that we can use for the application to
 * react to certain events that occur inside the persistence mechanism. However, Hibernate only
 * supports a few basic use cases, e.g. defining a {@link PostLoad} method directly in an {@link
 * javax.persistence.Entity} class or in an {@link Embeddable} class. If the annotated method is
 * defined in an {@link Embeddable} class that is a property of another {@link Embeddable} class, or
 * it is defined in a parent class of the {@link Embeddable} class, Hibernate doesn't invoke it.
 *
 * @see <a href="https://hibernate.atlassian.net/browse/HHH-13316">HHH-13316</a>
 */
public class EntityCallbacksListener {

  @PrePersist
  void prePersist(Object entity) {
    EntityCallbackExecutor.create(PrePersist.class).execute(entity, entity.getClass());
  }

  @PreRemove
  void preRemove(Object entity) {
    EntityCallbackExecutor.create(PreRemove.class).execute(entity, entity.getClass());
  }

  @PostPersist
  void postPersist(Object entity) {
    EntityCallbackExecutor.create(PostPersist.class).execute(entity, entity.getClass());
  }

  @PostRemove
  void postRemove(Object entity) {
    EntityCallbackExecutor.create(PostRemove.class).execute(entity, entity.getClass());
  }

  @PreUpdate
  void preUpdate(Object entity) {
    EntityCallbackExecutor.create(PreUpdate.class).execute(entity, entity.getClass());
  }

  @PostUpdate
  void postUpdate(Object entity) {
    EntityCallbackExecutor.create(PostUpdate.class).execute(entity, entity.getClass());
  }

  @PostLoad
  void postLoad(Object entity) {
    EntityCallbackExecutor.create(PostLoad.class).execute(entity, entity.getClass());
  }

  private static class EntityCallbackExecutor {
    Class<? extends Annotation> callbackType;

    private EntityCallbackExecutor(Class<? extends Annotation> callbackType) {
      this.callbackType = callbackType;
    }

    private static EntityCallbackExecutor create(Class<? extends Annotation> callbackType) {
      return new EntityCallbackExecutor(callbackType);
    }

    private void execute(Object entity, Class<?> entityType) {
      Class<?> parentType = entityType.getSuperclass();
      if (parentType != null && parentType.isAnnotationPresent(MappedSuperclass.class)) {
        execute(entity, parentType);
      }

      findEmbeddedProperties(entity, entityType)
          .forEach(
              normalEmbedded -> {
                // For each normal embedded property, we don't execute its @PostLoad method because
                // it is handled by Hibernate. However, for the embedded property defined in the
                // entity's parent class, we need to treat it as a nested embedded property and
                // invoke its callback function.
                if (entity.getClass().equals(entityType)) {
                  executeCallbackForNormalEmbeddedProperty(
                      normalEmbedded, normalEmbedded.getClass());
                } else {
                  executeCallbackForNestedEmbeddedProperty(
                      normalEmbedded, normalEmbedded.getClass());
                }
              });
    }

    private void executeCallbackForNestedEmbeddedProperty(
        Object nestedEmbeddedObject, Class<?> nestedEmbeddedType) {
      Class<?> parentType = nestedEmbeddedType.getSuperclass();
      if (parentType != null && parentType.isAnnotationPresent(MappedSuperclass.class)) {
        executeCallbackForNestedEmbeddedProperty(nestedEmbeddedObject, parentType);
      }

      findEmbeddedProperties(nestedEmbeddedObject, nestedEmbeddedType)
          .forEach(
              embeddedProperty ->
                  executeCallbackForNestedEmbeddedProperty(
                      embeddedProperty, embeddedProperty.getClass()));

      for (Method method : nestedEmbeddedType.getDeclaredMethods()) {
        if (method.isAnnotationPresent(callbackType)) {
          invokeMethod(method, nestedEmbeddedObject);
        }
      }
    }

    private void executeCallbackForNormalEmbeddedProperty(
        Object normalEmbeddedObject, Class<?> normalEmbeddedType) {
      Class<?> parentType = normalEmbeddedType.getSuperclass();
      if (parentType != null && parentType.isAnnotationPresent(MappedSuperclass.class)) {
        executeCallbackForNormalEmbeddedProperty(normalEmbeddedObject, parentType);
      }

      findEmbeddedProperties(normalEmbeddedObject, normalEmbeddedType)
          .forEach(
              embeddedProperty ->
                  executeCallbackForNestedEmbeddedProperty(
                      embeddedProperty, embeddedProperty.getClass()));
    }

    private Stream<Object> findEmbeddedProperties(Object object, Class<?> clazz) {
      return Arrays.stream(clazz.getDeclaredFields())
          .filter(
              field ->
                  field.isAnnotationPresent(Embedded.class)
                      || field.getType().isAnnotationPresent(Embeddable.class))
          .map(field -> getFieldObject(field, object))
          .filter(Objects::nonNull);
    }

    private static Object getFieldObject(Field field, Object object) {
      field.setAccessible(true);
      try {
        return field.get(object);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static void invokeMethod(Method method, Object object) {
      method.setAccessible(true);
      try {
        method.invoke(object);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
