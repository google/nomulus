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

package google.registry.flows.custom;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;

/** A record that encapsulates database entities to insert, update, and delete. */
public record EntityChanges(
    ImmutableSet<ImmutableObject> inserts,
    ImmutableSet<ImmutableObject> updates,
    ImmutableSet<VKey<ImmutableObject>> deletes) {

  public ImmutableSet<ImmutableObject> getInserts() {
    return inserts;
  }

  public ImmutableSet<ImmutableObject> getUpdates() {
    return updates;
  }

  public ImmutableSet<VKey<ImmutableObject>> getDeletes() {
    return deletes;
  }

  public static Builder newBuilder() {
    // Default inserts, updates, and deletes to empty sets, so that the build() method won't
    // subsequently throw an exception if one doesn't end up being applicable.
    return new AutoBuilder_EntityChanges_Builder()
        .setInserts(ImmutableSet.of())
        .setUpdates(ImmutableSet.of())
        .setDeletes(ImmutableSet.of());
  }

  /** Builder for {@link EntityChanges}. */
  @AutoBuilder
  public interface Builder {

    Builder setInserts(ImmutableSet<ImmutableObject> entitiesToInsert);

    ImmutableSet.Builder<ImmutableObject> insertsBuilder();

    default Builder addInsert(ImmutableObject entityToInsert) {
      insertsBuilder().add(entityToInsert);
      return this;
    }

    Builder setUpdates(ImmutableSet<ImmutableObject> entitiesToUpdate);

    ImmutableSet.Builder<ImmutableObject> updatesBuilder();

    default Builder addUpdate(ImmutableObject entityToUpdate) {
      updatesBuilder().add(entityToUpdate);
      return this;
    }

    Builder setDeletes(ImmutableSet<VKey<ImmutableObject>> entitiesToDelete);

    ImmutableSet.Builder<VKey<ImmutableObject>> deletesBuilder();

    default Builder addDelete(VKey<ImmutableObject> entityToDelete) {
      deletesBuilder().add(entityToDelete);
      return this;
    }

    EntityChanges build();
  }
}
