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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;

/** A wrapper class that encapsulates Datastore entities to both save and delete. */
@AutoValue
public abstract class EntityChanges {

  public abstract ImmutableSet<ImmutableObject> getSaves();

  public abstract ImmutableSet<VKey<ImmutableObject>> getDeletes();

  public static Builder newBuilder() {
    // Default both entities to save and entities to delete to empty sets, so that the build()
    // method won't subsequently throw an exception if one doesn't end up being applicable.
    return new AutoValue_EntityChanges.Builder()
        .setSaves(ImmutableSet.of())
        .setDeletes(ImmutableSet.of());
  }

  /** Builder for {@link EntityChanges}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setSaves(ImmutableSet<ImmutableObject> entitiesToSave);

    public abstract ImmutableSet.Builder<ImmutableObject> savesBuilder();

    public Builder addSave(ImmutableObject entityToSave) {
      savesBuilder().add(entityToSave);
      return this;
    }

    public abstract Builder setDeletes(ImmutableSet<VKey<ImmutableObject>> entitiesToDelete);

    public abstract ImmutableSet.Builder<VKey<ImmutableObject>> deletesBuilder();

    public Builder addDelete(VKey<ImmutableObject> entityToDelete) {
      deletesBuilder().add(entityToDelete);
      return this;
    }

    public abstract EntityChanges build();
  }
}
