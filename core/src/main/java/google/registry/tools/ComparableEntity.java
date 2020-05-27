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

import com.google.appengine.api.datastore.Entity;
import com.google.auto.value.AutoValue;
import com.google.common.base.Objects;

/** Wraps {@link Entity} to do hashCode/equals based on both the entity's key and its properties. */
final class ComparableEntity {
  private static final String TEST_ENTITY_KIND = "TestEntity";

  private final Entity entity;

  ComparableEntity(Entity entity) {
    this.entity = entity;
  }

  public Entity getEntity() {
    return entity;
  }

  @Override
  public boolean equals(Object that) {
    if (that instanceof ComparableEntity) {
      ComparableEntity thatEntity = (ComparableEntity) that;
      return entity.equals(thatEntity.entity)
          && entity.getProperties().equals(thatEntity.entity.getProperties());
    }

    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(entity.getKey(), entity.getProperties());
  }

  @Override
  public String toString() {
    return "ComparableEntity(" + entity + ")";
  }

  public static ComparableEntity from(int id, Property... properties) {
    Entity entity = new Entity(TEST_ENTITY_KIND, id);
    for (Property prop : properties) {
      entity.setProperty(prop.name(), prop.value());
    }
    return new ComparableEntity(entity);
  }

  @AutoValue
  abstract static class Property {

    static Property create(String name, Object value) {
      return new AutoValue_ComparableEntity_Property(name, value);
    }

    abstract String name();

    abstract Object value();
  }
}
