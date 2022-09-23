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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.Host;
import google.registry.testing.TestObject;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ClassPathManager}. */
public class ClassPathManagerTest {
  @Test
  void getClass_classInClassRegistry_returnsClass() throws ClassNotFoundException {
    /*
     * Class names are used in stringified vkeys, which can be present in task queues. Class name is
     * required to create a vkey. Changing these names could break task queue entries that are
     * present during a rollout. If you want to change the names of any of the classses supported in
     * CLASS_REGISTRY, you'll need to introduce some mechanism to deal with this. One way is to find
     * the corresponding class name by calling ClassPathManager.getClassName(clazz). The classes
     * below are all classes supported in CLASS_REGISTRY. This test breaks if someone changes a
     * classname without preserving the original name.
     */
    assertThat(ClassPathManager.getClass("Host")).isEqualTo(Host.class);
    assertThat(ClassPathManager.getClass("Contact")).isEqualTo(Contact.class);
    assertThat(ClassPathManager.getClass("GaeUserIdConverter")).isEqualTo(GaeUserIdConverter.class);
    assertThat(ClassPathManager.getClass("Domain")).isEqualTo(Domain.class);
  }

  @Test
  void getClass_classNotInClassRegistry_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> ClassPathManager.getClass("DomainHistory"));
    assertThat(thrown).hasMessageThat().contains("Class DomainHistory not found in class registry");
  }

  @Test
  void getClassName_classNotInClassRegistry_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> ClassPathManager.getClassName(DomainHistory.class));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Class DomainHistory not found in class name registry");
  }

  @Test
  void getClassName() {
    /*
     * Class names are used in stringified vkeys, which can be present in task queues. Class name is
     * required to create a vkey. Changing these names could break task queue entries that are
     * present during a rollout. If you want to change the names of any of the classses supported in
     * CLASS_NAME_REGISTRY, you'll need to introduce some mechanism to deal with this.
     * ClassPathManager.getClassName(clazz) allows you to verify the corresponding name of a class.
     * The classes below are all classes supported in CLASS_NAME_REGISTRY. This test breaks if
     * someone changes a classname without preserving the original name.
     */
    assertThat(ClassPathManager.getClassName(Host.class)).isEqualTo("Host");
    assertThat(ClassPathManager.getClassName(Contact.class)).isEqualTo("Contact");
    assertThat(ClassPathManager.getClassName(GaeUserIdConverter.class))
        .isEqualTo("GaeUserIdConverter");
    assertThat(ClassPathManager.getClassName(Domain.class)).isEqualTo("Domain");
  }

  @Test
  void addTestEntityClass_success() {
    ClassPathManager.addTestEntityClass(TestObject.class);
    assertThat(ClassPathManager.getClass("TestObject")).isEqualTo(TestObject.class);
  }
}
