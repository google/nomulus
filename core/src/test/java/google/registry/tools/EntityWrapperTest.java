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

import static com.google.common.truth.Truth.assertThat;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.collect.ImmutableList;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import com.google.storage.onestore.v3.OnestoreEntity.Property;
import google.registry.testing.AppEngineRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link EntityWrapper}. */
public final class EntityWrapperTest {

  private static final String TEST_ENTITY_KIND = "TestEntity";
  private static final int ARBITRARY_KEY_ID = 1001;

  @RegisterExtension
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Test
  void testEquals() {
    // Create an entity with a key and some properties.
    Entity entity = new Entity(TEST_ENTITY_KIND, ARBITRARY_KEY_ID);
    // Note that we need to specify these as long for property comparisons to work because that's
    // how they are deserialized from protos.
    entity.setProperty("eeny", 100L);
    entity.setProperty("meeny", 200L);
    entity.setProperty("miney", 300L);

    EntityProto proto1 = EntityTranslator.convertToPb(entity);
    EntityProto proto2 = EntityTranslator.convertToPb(entity);

    // Reorder the property list of proto2 (the protobuf stores this as a repeated field, so
    // we just have to clear and re-add them in a different order).
    ImmutableList<Property> properties =
        ImmutableList.of(proto2.getProperty(2), proto2.getProperty(0), proto2.getProperty(1));
    proto2.clearProperty();
    for (Property property : properties) {
      proto2.addProperty(property);
    }

    // Construct entity objects from the two protos.
    Entity e1 = EntityTranslator.createFromPb(proto1);
    Entity e2 = EntityTranslator.createFromPb(proto2);

    // Ensure that we have a normalized representation.
    EntityWrapper ce1 = new EntityWrapper(e1);
    EntityWrapper ce2 = new EntityWrapper(e2);
    assertThat(ce1).isEqualTo(ce2);
    assertThat(ce1.hashCode()).isEqualTo(ce2.hashCode());

    // Ensure that the original entity is equal.
    assertThat(new EntityWrapper(entity)).isEqualTo(ce1);
  }

  @Test
  void testDifferentPropertiesNotEqual() {
    Entity entity = new Entity(TEST_ENTITY_KIND, ARBITRARY_KEY_ID);
    // Note that we need to specify these as long for property comparisons to work because that's
    // how they are deserialized from protos.
    entity.setProperty("eeny", 100L);
    entity.setProperty("meeny", 200L);
    entity.setProperty("miney", 300L);

    EntityProto proto1 = EntityTranslator.convertToPb(entity);

    entity.setProperty("tiger!", 400);
    EntityProto proto2 = EntityTranslator.convertToPb(entity);

    // Construct entity objects from the two protos.
    Entity e1 = EntityTranslator.createFromPb(proto1);
    Entity e2 = EntityTranslator.createFromPb(proto2);

    EntityWrapper ce1 = new EntityWrapper(e1);
    EntityWrapper ce2 = new EntityWrapper(e2);
    assertThat(e1).isEqualTo(e2); // The keys should still be the same.
    assertThat(ce1).isNotEqualTo(ce2);
    assertThat(ce1.hashCode()).isNotEqualTo(ce2.hashCode());
  }

  @Test
  void testDifferentKeysNotEqual() {
    EntityProto proto1 =
        EntityTranslator.convertToPb(new Entity(TEST_ENTITY_KIND, ARBITRARY_KEY_ID));
    EntityProto proto2 =
        EntityTranslator.convertToPb(new Entity(TEST_ENTITY_KIND, ARBITRARY_KEY_ID + 1));

    // Construct entity objects from the two protos.
    Entity e1 = EntityTranslator.createFromPb(proto1);
    Entity e2 = EntityTranslator.createFromPb(proto2);

    EntityWrapper ce1 = new EntityWrapper(e1);
    EntityWrapper ce2 = new EntityWrapper(e2);
    assertThat(ce1).isNotEqualTo(ce2);
    assertThat(ce1.hashCode()).isNotEqualTo(ce2.hashCode());
  }

  @Test
  void testComparisonAgainstNonComparableEntities() {
    EntityWrapper ce = new EntityWrapper(new Entity(TEST_ENTITY_KIND, ARBITRARY_KEY_ID));
    // Note: this has to be "isNotEqualTo()" and not isNotNull() because we want to test the
    // equals() method and isNotNull() just checks for "ce != null".
    assertThat(ce).isNotEqualTo(null);
    assertThat(ce).isNotEqualTo(new Object());
  }
}
