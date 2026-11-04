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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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
import javax.annotation.Nullable;

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

  private static final ImmutableSet<Class<? extends Annotation>> CALLBACK_TYPES =
      ImmutableSet.of(
          RecursivePrePersist.class,
          RecursivePreRemove.class,
          RecursivePostPersist.class,
          RecursivePostRemove.class,
          RecursivePreUpdate.class,
          RecursivePostUpdate.class,
          RecursivePostLoad.class);

  private static final ClassValue<ClassCallbackInfo> CALLBACK_INFO_CACHE =
      new ClassValue<>() {
        @Override
        protected ClassCallbackInfo computeValue(Class<?> clazz) {
          return ClassCallbackInfo.create(clazz);
        }
      };

  @PrePersist
  void prePersist(Object entity) {
    execute(entity, RecursivePrePersist.class);
  }

  @PreRemove
  void preRemove(Object entity) {
    execute(entity, RecursivePreRemove.class);
  }

  @PostPersist
  void postPersist(Object entity) {
    execute(entity, RecursivePostPersist.class);
  }

  @PostRemove
  void postRemove(Object entity) {
    execute(entity, RecursivePostRemove.class);
  }

  @PreUpdate
  void preUpdate(Object entity) {
    execute(entity, RecursivePreUpdate.class);
  }

  @PostUpdate
  void postUpdate(Object entity) {
    execute(entity, RecursivePostUpdate.class);
  }

  @PostLoad
  void postLoad(Object entity) {
    execute(entity, RecursivePostLoad.class);
  }

  private static void execute(Object entity, Class<? extends Annotation> callbackType) {
    CALLBACK_INFO_CACHE.get(entity.getClass()).execute(entity, callbackType);
  }

  @VisibleForTesting
  static ClassCallbackInfo getCallbackInfoForTesting(Class<?> clazz) {
    return CALLBACK_INFO_CACHE.get(clazz);
  }

  /**
   * Pre-computed reflection metadata for a single class, cached via {@link #CALLBACK_INFO_CACHE} so
   * that field and method lookups only happen once per class.
   */
  @VisibleForTesting
  record ClassCallbackInfo(
      @Nullable ClassCallbackInfo superclassInfo,
      ImmutableList<Field> embeddedFields,
      ImmutableMap<Class<? extends Annotation>, Method> methodsByCallback) {

    private static ClassCallbackInfo create(Class<?> currentClass) {
      Class<?> superclass = currentClass.getSuperclass();
      ClassCallbackInfo superclassInfo =
          (superclass != null && superclass.isAnnotationPresent(MappedSuperclass.class))
              ? CALLBACK_INFO_CACHE.get(superclass)
              : null;

      ImmutableList<Field> embeddedFields = getRelevantEmbeddedFields(currentClass);

      ImmutableMap.Builder<Class<? extends Annotation>, Method> methodsBuilder =
          new ImmutableMap.Builder<>();
      for (Method method : currentClass.getDeclaredMethods()) {
        for (Class<? extends Annotation> callbackType : CALLBACK_TYPES) {
          if (method.isAnnotationPresent(callbackType)) {
            method.setAccessible(true);
            methodsBuilder.put(callbackType, method);
          }
        }
      }

      return new ClassCallbackInfo(superclassInfo, embeddedFields, methodsBuilder.buildOrThrow());
    }

    /**
     * Executes eligible callbacks on {@code entity}, its superclasses, and its {@link Embedded}
     * properties.
     */
    private void execute(Object entity, Class<? extends Annotation> callbackType) {
      if (superclassInfo != null) {
        superclassInfo.execute(entity, callbackType);
      }

      for (Field field : embeddedFields) {
        Object embeddedProperty = getFieldObject(field, entity);
        if (embeddedProperty != null) {
          CALLBACK_INFO_CACHE
              .get(embeddedProperty.getClass())
              .execute(embeddedProperty, callbackType);
        }
      }

      Method method = methodsByCallback.get(callbackType);
      if (method != null) {
        invokeMethod(method, entity);
      }
    }

    private static ImmutableList<Field> getRelevantEmbeddedFields(Class<?> clazz) {
      return Arrays.stream(clazz.getDeclaredFields())
          .filter(field -> !field.isAnnotationPresent(Transient.class))
          .filter(
              field ->
                  field.isAnnotationPresent(Embedded.class)
                      || field.getType().isAnnotationPresent(Embeddable.class))
          .filter(field -> !Modifier.isStatic(field.getModifiers()))
          .peek(field -> field.setAccessible(true))
          .collect(toImmutableList());
    }

    private static Object getFieldObject(Field field, Object object) {
      try {
        return field.get(object);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    private static void invokeMethod(Method method, Object object) {
      try {
        method.invoke(object);
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
