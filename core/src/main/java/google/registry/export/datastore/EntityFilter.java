// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.export.datastore;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.client.json.GenericJson;
import com.google.api.client.util.Key;
import com.google.common.collect.ImmutableList;
import google.registry.model.annotations.DeleteAfterMigration;
import java.util.Collection;
import java.util.List;

/**
 * Model object that describes the Cloud Datastore 'kinds' to be exported or imported. The JSON form
 * of this type is found in export/import requests and responses.
 *
 * <p>Please note that properties not used by Domain Registry are not included, e.g., {@code
 * namespaceIds}.
 */
@DeleteAfterMigration
public class EntityFilter extends GenericJson {

  @Key private List<String> kinds = ImmutableList.of();

  /** For JSON deserialization. */
  public EntityFilter() {}

  EntityFilter(Collection<String> kinds) {
    checkNotNull(kinds, "kinds");
    this.kinds = ImmutableList.copyOf(kinds);
  }

  List<String> getKinds() {
    return ImmutableList.copyOf(kinds);
  }
}
