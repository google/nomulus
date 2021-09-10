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

import com.google.apphosting.api.ApiProxy;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.BackupGroupRoot;
import google.registry.model.replay.DatastoreOnlyEntity;
import javax.annotation.Nullable;

/**
 * The root key for the entity group which is known as the cross-tld entity group for historical
 * reasons.
 *
 * <p>This exists as a storage place for common configuration options and global settings that
 * aren't updated too frequently. Entities in this entity group are usually cached upon load. The
 * reason this common entity group exists is because it enables strongly consistent queries and
 * updates across this seldomly updated data. This shared entity group also helps cut down on a
 * potential ballooning in the number of entity groups enlisted in transactions.
 *
 * <p>Historically, each TLD used to have a separate namespace, and all entities for a TLD were in a
 * single EntityGroupRoot for that TLD. Hence why there was a "cross-tld" entity group -- it was the
 * entity group for the single namespace where global data applicable for all TLDs lived.
 */
@Entity
public class EntityGroupRoot extends BackupGroupRoot implements DatastoreOnlyEntity {

  @SuppressWarnings("unused")
  @Id
  private String id;

  /** The root key for cross-tld resources such as registrars. */
  public static @Nullable Key<EntityGroupRoot> getCrossTldKey() {
    // If we cannot get a current environment, calling Key.create() will fail. Instead we return a
    // null in cases where this key is not actually needed (for example when loading an entity from
    // SQL) to initialize an object, to avoid having to register a DatastoreEntityExtension in
    // tests.
    return ApiProxy.getCurrentEnvironment() == null
        ? null
        : Key.create(EntityGroupRoot.class, "cross-tld");
  }
}
