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

package google.registry.model.ofy;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.testing.DatabaseHelper.persistResources;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.InCrossTld;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.replay.EntityTest.EntityForTesting;
import google.registry.testing.AppEngineExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DatastoreTransactionManager}. */
public class DatastoreTransactionManagerTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastore()
          .withOfyTestEntities(InCrossTldTestEntity.class)
          .build();

  @Test
  void test_loadAllOf_usesAncestorQuery() {
    InCrossTldTestEntity foo = new InCrossTldTestEntity("foo");
    InCrossTldTestEntity bar = new InCrossTldTestEntity("bar");
    InCrossTldTestEntity baz = new InCrossTldTestEntity("baz");
    baz.parent = null;
    persistResources(ImmutableList.of(foo, bar, baz));
    // baz is excluded by the cross-TLD ancestor query
    assertThat(ofyTm().loadAllOf(InCrossTldTestEntity.class)).containsExactly(foo, bar);
  }

  @Entity
  @EntityForTesting
  @InCrossTld
  private static class InCrossTldTestEntity extends ImmutableObject {

    @Id String name;
    @Parent Key<EntityGroupRoot> parent = getCrossTldKey();

    private InCrossTldTestEntity(String name) {
      this.name = name;
    }

    // Needs to exist to make Objectify happy.
    @SuppressWarnings("unused")
    private InCrossTldTestEntity() {}
  }
}
