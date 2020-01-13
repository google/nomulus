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

package google.registry.tools.server;

import static com.google.appengine.api.datastore.DatastoreServiceFactory.getDatastoreService;
import static com.google.common.truth.Truth.assertThat;
import static com.googlecode.objectify.Key.create;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static org.junit.Assert.assertThrows;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.KeyFactory;
import google.registry.model.registry.label.ReservedList;
import google.registry.request.HttpException.BadRequestException;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeResponse;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DeleteEntityAction}. */
@RunWith(JUnit4.class)
public class DeleteEntityActionTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();
  FakeResponse response = new FakeResponse();

  @Test
  public void test_deleteSingleRawEntitySuccessfully() {
    Entity entity = new Entity("single", "raw");
    getDatastoreService().put(entity);
    new DeleteEntityAction(KeyFactory.keyToString(entity.getKey()), response).run();
    assertThat(response.getPayload()).isEqualTo("Deleted 1 raw entities and 0 registered entities");
  }

  @Test
  public void test_deleteSingleRegisteredEntitySuccessfully() {
    ReservedList ofyEntity = new ReservedList.Builder().setName("foo").build();
    ofy().saveWithoutBackup().entity(ofyEntity).now();
    new DeleteEntityAction(KeyFactory.keyToString(create(ofyEntity).getRaw()), response).run();
    assertThat(response.getPayload()).isEqualTo("Deleted 0 raw entities and 1 registered entities");
  }

  @Test
  public void test_deletePolymorphicEntity_fallbackSucceedsForUnregisteredType() {
    Entity entity = new Entity("single", "raw");
    entity.setIndexedProperty("^d", "UnregType");
    getDatastoreService().put(entity);
    new DeleteEntityAction(KeyFactory.keyToString(entity.getKey()), response).run();
    assertThat(response.getPayload()).isEqualTo("Deleted 1 raw entities and 0 registered entities");
  }

  @Test
  public void test_deleteOneRawEntityAndOneRegisteredEntitySuccessfully() {
    Entity entity = new Entity("first", "raw");
    getDatastoreService().put(entity);
    String rawKey = KeyFactory.keyToString(entity.getKey());
    ReservedList ofyEntity = new ReservedList.Builder().setName("registered").build();
    ofy().saveWithoutBackup().entity(ofyEntity).now();
    String ofyKey = KeyFactory.keyToString(create(ofyEntity).getRaw());
    new DeleteEntityAction(String.format("%s,%s", rawKey, ofyKey), response).run();
    assertThat(response.getPayload()).isEqualTo("Deleted 1 raw entities and 1 registered entities");
  }

  @Test
  public void test_deleteNonExistentEntityRepliesWithError() {
    Entity entity = new Entity("not", "here");
    String rawKey = KeyFactory.keyToString(entity.getKey());
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> new DeleteEntityAction(rawKey, response).run());
    assertThat(thrown).hasMessageThat().contains("Could not find entity with key " + rawKey);
  }

  @Test
  public void test_deleteOneEntityAndNonExistentEntityRepliesWithError() {
    ReservedList ofyEntity = new ReservedList.Builder().setName("first_registered").build();
    ofy().saveWithoutBackup().entity(ofyEntity).now();
    String ofyKey = KeyFactory.keyToString(create(ofyEntity).getRaw());
    String rawKey = KeyFactory.keyToString(new Entity("non", "existent").getKey());
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> new DeleteEntityAction(String.format("%s,%s", ofyKey, rawKey), response).run());
    assertThat(thrown).hasMessageThat().contains("Could not find entity with key " + rawKey);
  }
}
