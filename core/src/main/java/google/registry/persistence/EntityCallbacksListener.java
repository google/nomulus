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

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A listener class to invoke entity callbacks.
 *
 * <p>JPA defines a few annotations, e.g. {@link PostLoad}, that we can use for the application to
 * react to certain events that occur inside the persistence mechanism. However, Hibernate will not
 * (or at least, is not guaranteed to) call these methods in embedded fields. Because we still want
 * to invoke these event-based methods on embedded objects, we inspect the type tree for all {@link
 * Embeddable} classes or {@link Embedded} objects.
 *
 * <p>This listener is added in core/src/main/resources/META-INF/orm.xml as a default entity
 * listener whose annotated methods will be invoked by Hibernate when corresponding events happen.
 * For example, {@link EntityCallbacksListener#prePersist} will be invoked before the entity is
 * persisted to the database, then it will recursively invoke any other {@link RecursivePrePersist}
 * method that we want to invoke, but that Hibernate does not support.
 *
 * @see <a
 *     href="https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#events-jpa-callbacks">JPA
 *     Callbacks</a>
 * @see <a href="https://hibernate.atlassian.net/browse/HHH-12326">HHH-12326</a>
 */
public class EntityCallbacksListener {

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface RecursivePrePersist {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface RecursivePreRemove {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface RecursivePostPersist {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface RecursivePostRemove {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface RecursivePreUpdate {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface RecursivePostUpdate {}

  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface RecursivePostLoad {}

  @PrePersist
  void prePersist(Object entity) {
    EntityCallbackExecutor.create(RecursivePrePersist.class).execute(entity, entity.getClass());
  }

  @PreRemove
  void preRemove(Object entity) {
    EntityCallbackExecutor.create(RecursivePreRemove.class).execute(entity, entity.getClass());
  }

  @PostPersist
  void postPersist(Object entity) {
    EntityCallbackExecutor.create(RecursivePostPersist.class).execute(entity, entity.getClass());
  }

  @PostRemove
  void postRemove(Object entity) {
    EntityCallbackExecutor.create(RecursivePostRemove.class).execute(entity, entity.getClass());
  }

  @PreUpdate
  void preUpdate(Object entity) {
    EntityCallbackExecutor.create(RecursivePreUpdate.class).execute(entity, entity.getClass());
  }

  @PostUpdate
  void postUpdate(Object entity) {
    EntityCallbackExecutor.create(RecursivePostUpdate.class).execute(entity, entity.getClass());
  }

  @PostLoad
  void postLoad(Object entity) {
    EntityCallbackExecutor.create(RecursivePostLoad.class).execute(entity, entity.getClass());
  }

  private static class EntityCallbackExecutor {
    Class<? extends Annotation> callbackType;

    private EntityCallbackExecutor(Class<? extends Annotation> callbackType) {
      this.callbackType = callbackType;
    }

    private static EntityCallbackExecutor create(Class<? extends Annotation> callbackType) {
      return new EntityCallbackExecutor(callbackType);
    }

    /**
     * Executes eligible callbacks in {@link Embedded} properties recursively.
     *
     * <p>We pass the class that we're currently inspecting because embedded properties or relevant
     * methods can be defined on a possible superclass of the root entity class.
     *
     * @param entity the Java object of the entity class
     */
    private void execute(Object entity, Class<?> currentClass) {
      Class<?> superclass = currentClass.getSuperclass();
      if (superclass != null && superclass.isAnnotationPresent(MappedSuperclass.class)) {
        execute(entity, superclass);
      }

      findEmbeddedProperties(entity, currentClass)
          .forEach(embeddedProperty -> execute(embeddedProperty, embeddedProperty.getClass()));

      for (Method method : currentClass.getDeclaredMethods()) {
        if (method.isAnnotationPresent(callbackType)) {
          invokeMethod(method, entity);
        }
      }
    }

    private Stream<Object> findEmbeddedProperties(Object object, Class<?> currentClass) {
      return Arrays.stream(currentClass.getDeclaredFields())
          .filter(field -> !field.isAnnotationPresent(Transient.class))
          .filter(
              field ->
                  field.isAnnotationPresent(Embedded.class)
                      || field.getType().isAnnotationPresent(Embeddable.class))
          .filter(field -> !Modifier.isStatic(field.getModifiers()))
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
