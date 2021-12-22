// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.collect.Ordering.arbitrary;
import static google.registry.model.EntityClasses.ALL_CLASSES;
import static java.lang.ClassLoader.getSystemClassLoader;
import static java.lang.reflect.Modifier.isAbstract;

import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.common.collect.TreeMultimap;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.BackupGroupRoot;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.VirtualEntity;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Visualizes the schema parentage tree. */
@DeleteAfterMigration
@Parameters(commandDescription = "Generate a model schema file")
final class GetSchemaTreeCommand implements Command {

  /** Mapping from parent classes in the Datastore sense to child classes. */
  private final Multimap<Class<?>, Class<?>> hierarchy =
      TreeMultimap.create(arbitrary(), new PrintableNameOrdering());

  /** Mapping from superclasses used in parentage to concrete subclasses. */
  private Multimap<Class<?>, Class<?>> superclassToSubclasses;

  @Override
  public void run() {
    // Get the @Parent type for each class.
    Map<Class<?>, Class<?>> entityToParentType = new HashMap<>();
    for (Class<?> clazz : ALL_CLASSES) {
      entityToParentType.put(clazz, getParentType(clazz));
    }
    // Find super types like EppResource that are used as parents in place of actual entity types.
    Set<Class<?>> superclasses = new HashSet<>();
    for (Class<?> clazz : ALL_CLASSES) {
      Class<?> parentType = entityToParentType.get(clazz);
      if (!ALL_CLASSES.contains(parentType) && !Object.class.equals(parentType)) {
        superclasses.add(parentType);
      }
    }
    // Find the subclasses for each superclass we just found, and map them to their superclasses.
    Map<Class<?>, Class<?>> subclassToSuperclass = new HashMap<>();
    for (Class<?> clazz : ALL_CLASSES) {
      for (Class<?> superclass : superclasses) {
        if (superclass.isAssignableFrom(clazz)) {
          subclassToSuperclass.put(clazz, superclass);
          break;
        }
      }
    }
    // Map @EntitySubclass classes to their superclasses.
    for (Class<?> clazz : ALL_CLASSES) {
      if (clazz.isAnnotationPresent(EntitySubclass.class)) {
        Class<?> entityClass = clazz;
        while (!entityClass.isAnnotationPresent(Entity.class)) {
          entityClass = entityClass.getSuperclass();
        }
        if (subclassToSuperclass.containsKey(clazz)) {
          subclassToSuperclass.put(entityClass, subclassToSuperclass.get(clazz));
        }
        subclassToSuperclass.put(clazz, entityClass);
      }
    }
    // Build the parentage hierarchy, replacing subclasses with superclasses wherever possible.
    for (Class<?> clazz : ALL_CLASSES) {
      Class<?> superclass = clazz;
      while (subclassToSuperclass.containsKey(superclass)) {
        superclass = subclassToSuperclass.get(superclass);
      }
      hierarchy.put(entityToParentType.get(clazz), superclass == null ? clazz : superclass);
    }
    // Build up the superclass to subclass mapping.
    superclassToSubclasses = Multimaps.invertFrom(
        Multimaps.forMap(subclassToSuperclass),
        TreeMultimap.create(arbitrary(), new PrintableNameOrdering()));
    printTree(Object.class, 0);
  }

  private Class<?> getParentType(Class<?> clazz) {
    for (; clazz != null; clazz = clazz.getSuperclass()) {
      for (Field field : clazz.getDeclaredFields()) {
        if (field.isAnnotationPresent(Parent.class)) {
          try {
            return getSystemClassLoader().loadClass(
                ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0]
                    .toString()
                    .replace("? extends ", "")
                    .replace("class ", ""));
          } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }
    return Object.class;
  }

  private void printTree(Class<?> parent, int indent) {
    for (Class<?> clazz : hierarchy.get(parent)) {
      System.out.println(new StringBuilder(Strings.repeat(" ", indent))
          .append(indent == 0 ? "" : "↳ ")
          .append(getPrintableName(clazz))
          .append(isAbstract(clazz.getModifiers()) ? " (abstract)" : "")
          .append(clazz.isAnnotationPresent(VirtualEntity.class) ? " (virtual)" : "")
          .append(clazz.isAnnotationPresent(NotBackedUp.class) ? " (not backed up)" : "")
          .append(BackupGroupRoot.class.isAssignableFrom(clazz) ? " (bgr)" : ""));
      printSubclasses(clazz, indent + 2);
      printTree(clazz, indent + 2);
      if (indent == 0) {
        System.out.println();  // Separate the entity groups with a line.
      }
    }
  }

  private void printSubclasses(Class<?> parent, int indent) {
    for (Class<?> clazz : superclassToSubclasses.get(parent)) {
      System.out.println(new StringBuilder(Strings.repeat(" ", indent))
          .append("- ")
          .append(getPrintableName(clazz))
          .append(clazz.isAnnotationPresent(EntitySubclass.class) ? " (subclass)" : ""));
      printSubclasses(clazz, indent + 2);
      printTree(clazz, indent + 2);
    }
  }

  /** Returns the simple name of the class prefixed with its wrapper's simple name, if any. */
  static String getPrintableName(Class<?> clazz) {
    return clazz.isMemberClass()
       ? getPrintableName(clazz.getDeclaringClass()) + "." + clazz.getSimpleName()
       : clazz.getSimpleName();
  }

  /** An ordering that sorts on {@link #getPrintableName}. */
  static class PrintableNameOrdering extends Ordering<Class<?>> implements Serializable {
    @Override
    public int compare(Class<?> left, Class<?> right) {
      return getPrintableName(left).compareTo(getPrintableName(right));
    }
  }
}
