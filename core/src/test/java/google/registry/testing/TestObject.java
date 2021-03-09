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

package google.registry.testing;

import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.VirtualEntity;
import google.registry.model.common.EntityGroupRoot;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import google.registry.schema.replay.EntityTest.EntityForTesting;
import javax.persistence.Transient;

/** A test model object that can be persisted in any entity group. */
@Entity
@javax.persistence.Entity
@EntityForTesting
public class TestObject extends ImmutableObject implements DatastoreAndSqlEntity {

  public static int beforeSqlDeleteCallCount;

  @Parent @Transient Key<EntityGroupRoot> parent;

  @Id @javax.persistence.Id String id;

  String field;

  public String getId() {
    return id;
  }

  public String getField() {
    return field;
  }

  public static VKey<TestObject> createVKey(Key<TestObject> key) {
    return VKey.create(TestObject.class, key.getName(), key);
  }

  public static TestObject create(String id) {
    return create(id, null);
  }

  public static TestObject create(String id, String field) {
    return create(id, field, getCrossTldKey());
  }

  public static TestObject create(String id, String field, Key<EntityGroupRoot> parent) {
    TestObject instance = new TestObject();
    instance.id = id;
    instance.field = field;
    instance.parent = parent;
    return instance;
  }

  public static void beforeSqlDelete(VKey<TestObject> key) {
    beforeSqlDeleteCallCount++;
  }

  /** A test @VirtualEntity model object, which should not be persisted. */
  @Entity
  @VirtualEntity
  @EntityForTesting
  public static class TestVirtualObject extends ImmutableObject {

    @Id String id;

    /**
     * Expose a factory method for testing saves of virtual entities; in real life this would never
     * be needed for an actual @VirtualEntity.
     */
    public static TestVirtualObject create(String id) {
      TestVirtualObject instance = new TestVirtualObject();
      instance.id = id;
      return instance;
    }

    public static Key<TestVirtualObject> createKey(String id) {
      return Key.create(TestVirtualObject.class, id);
    }
  }
}
