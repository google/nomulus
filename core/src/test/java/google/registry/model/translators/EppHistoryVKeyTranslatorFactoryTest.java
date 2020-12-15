// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.translators;

import static google.registry.model.translators.EppHistoryVKeyTranslatorFactory.kindPathToVKeyClass;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/** Unit test for {@link EppHistoryVKeyTranslatorFactory}. */
class EppHistoryVKeyTranslatorFactoryTest {

  @Test
  void assertAllVKeyClassesHavingCreateFromOfyKeyMethod() {
    kindPathToVKeyClass.forEach(
        (kindPath, vKeyClass) -> {
          try {
            vKeyClass.getDeclaredMethod("create", com.googlecode.objectify.Key.class);
          } catch (NoSuchMethodException e) {
            fail("Missing static method create(com.googlecode.objectify.Key) on " + vKeyClass, e);
          }
        });
  }
}
