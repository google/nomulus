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

package google.registry.model.common;

import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.annotations.InCrossTld;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;

/** A singleton entity in Datastore. */
@DeleteAfterMigration
@MappedSuperclass
@InCrossTld
public abstract class CrossTldSingleton extends ImmutableObject {

  public static final long SINGLETON_ID = 1; // There is always exactly one of these.

  @Id @javax.persistence.Id long id = SINGLETON_ID;

  @Transient @Parent Key<EntityGroupRoot> parent = getCrossTldKey();
}
