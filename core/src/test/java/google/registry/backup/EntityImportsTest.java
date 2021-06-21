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

package google.registry.backup;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import com.google.storage.onestore.v3.OnestoreEntity.EntityProto;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatastoreEntityExtension;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class EntityImportsTest {

  @RegisterExtension
  @Order(value = 1)
  final DatastoreEntityExtension datastoreEntityExtension = new DatastoreEntityExtension();

  @RegisterExtension
  final AppEngineExtension appEngine =
      new AppEngineExtension.Builder().withDatastoreAndCloudSql().withoutCannedData().build();

  private DatastoreService datastoreService;

  @BeforeEach
  void beforeEach() {
    datastoreService = DatastoreServiceFactory.getDatastoreService();
  }

  @Test
  void importCommitLogs_keysFixed() throws Exception {
    // Input resource has several entities with appId == "AppId_1", which is different from that
    // of the AppEngineExtension ('test').
    try (InputStream commitLogInputStream =
        Resources.getResource("google/registry/backup/commitlog.data").openStream()) {
      ImmutableList<Entity> entities =
          loadEntityProtos(commitLogInputStream).stream()
              .map(EntityImports::fixEntity)
              .map(EntityTranslator::createFromPb)
              .collect(ImmutableList.toImmutableList());
      // Verifies that the original appId has been overwritten.
      assertThat(entities.get(0).getKey().getAppId()).isEqualTo("test");
      datastoreService.put(entities);
      // Imported entity can be found by Ofy after appId conversion.
      assertThat(auditedOfy().load().type(CommitLogCheckpoint.class).count()).isGreaterThan(0);
    }
  }

  private static ImmutableList<EntityProto> loadEntityProtos(InputStream inputStream) {
    ImmutableList.Builder<EntityProto> protosBuilder = new ImmutableList.Builder<>();
    while (true) {
      EntityProto proto = new EntityProto();
      boolean parsed = proto.parseDelimitedFrom(inputStream);
      if (parsed && proto.isInitialized()) {
        protosBuilder.add(proto);
      } else {
        break;
      }
    }
    return protosBuilder.build();
  }
}
