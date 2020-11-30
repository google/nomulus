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

package google.registry.model.ofy;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.ofy.TransactionInfo.Delete;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.AppEngineExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class EntityWritePrioritiesTest {

  @RegisterExtension
  AppEngineExtension appEngine = new AppEngineExtension.Builder().withDatastore().build();

  @Test
  void testGetPriority() {
    // just verify that the lowest is what we expect for both save and delete and verify that the
    // Registrar class is zero.
    ImmutableMap<Key<?>, Object> actions =
        ImmutableMap.of(
            Key.create(HistoryEntry.class, 100), TransactionInfo.Delete.SENTINEL,
            Key.create(HistoryEntry.class, 200), "fake history entry",
            Key.create(Registrar.class, 300), "fake registrar");
    ImmutableMap<Long, Integer> expectedValues =
        ImmutableMap.of(100L, EntityWritePriorities.DELETE_RANGE + 10, 200L, -10, 300L, 0);

    for (ImmutableMap.Entry<Key<?>, Object> entry : actions.entrySet()) {
      assertThat(
              EntityWritePriorities.getEntityPriority(
                  entry.getKey().getKind(), Delete.SENTINEL.equals(entry.getValue())))
          .isEqualTo(expectedValues.get(entry.getKey().getId()));
    }
  }
}
