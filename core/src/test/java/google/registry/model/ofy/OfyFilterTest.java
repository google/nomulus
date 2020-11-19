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

package google.registry.model.ofy;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.initOfy;
import static google.registry.testing.DatabaseHelper.newContactResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.ObjectifyFilter;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import google.registry.model.contact.ContactResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for our replacement Objectify filter. */
class OfyFilterTest {

  private LocalServiceTestHelper helper;
  private ObjectifyFactory factory;

  // We can't use AppEngineRule, because it triggers the precise behavior that we are testing.

  @BeforeEach
  void beforeEach() {
    helper = new LocalServiceTestHelper(new LocalDatastoreServiceTestConfig()).setUp();
    // Clear out the factory so that it requires re-registration on each test method.
    // Otherwise, static registration of types in one method would persist across methods.
    initOfy();
    factory = ObjectifyService.factory();
    ObjectifyService.setFactory(new ObjectifyFactory(false));
  }

  @AfterEach
  void afterEach() {
    ObjectifyFilter.complete();
    ObjectifyService.setFactory(factory);
    ObjectifyFilter.complete();
    helper.tearDown();
  }

  /**
   * Key.create looks up kind metadata for the class of the object it is given. If this happens
   * before the first reference to ObjectifyService, which statically triggers type registrations,
   * then the create will fail. Note that this is only a problem if the type in question doesn't
   * call ObjectifyService.allocateId() inside its own builder or create method, since if it does
   * that would trigger the statics as well. In this example, Registrar has a string id, so the bug
   * occurs, were it not for OfyFilter.
   */
  @Test
  void testFilterRegistersTypes() {
    UnregisteredEntity entity = new UnregisteredEntity(5L);
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> Key.create(entity));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "class google.registry.model.ofy.OfyFilterTest$UnregisteredEntity "
                + "has not been registered");
  }

  /** The filter should register all types for us. */
  @Test
  void testKeyCreateAfterFilter() {
    new OfyFilter().init(null);
    ContactResource contact = newContactResource("contact1234");
    Key.create(contact);
  }

  @Entity
  private static class UnregisteredEntity {

    @Id long id;

    UnregisteredEntity(long id) {
      this.id = id;
    }
  }
}
