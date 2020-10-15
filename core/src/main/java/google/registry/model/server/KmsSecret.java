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

package google.registry.model.server;

import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.common.EntityGroupRoot;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;

/** Pointer to the latest {@link KmsSecretRevision}. */
@Entity
@ReportedOn
public class KmsSecret extends ImmutableObject implements DatastoreEntity {

  /** The unique name of this {@link KmsSecret}. */
  @Id String name;

  @Parent Key<EntityGroupRoot> parent = getCrossTldKey();

  /** The pointer to the latest {@link KmsSecretRevision}. */
  Key<KmsSecretRevision> latestRevision;

  public String getName() {
    return name;
  }

  public Key<KmsSecretRevision> getLatestRevision() {
    return latestRevision;
  }

  @Override
  public ImmutableList<SqlEntity> toSqlEntities() {
    return ImmutableList.of(); // not persisted in SQL
  }

  public static KmsSecret create(String name, KmsSecretRevision latestRevision) {
    KmsSecret instance = new KmsSecret();
    instance.name = name;
    instance.latestRevision = Key.create(latestRevision);
    return instance;
  }
}
