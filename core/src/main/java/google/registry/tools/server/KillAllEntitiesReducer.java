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

import static com.google.common.collect.Iterators.partition;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.googlecode.objectify.Key;
import java.util.Iterator;
import java.util.List;

/** Reducer that deletes a group of keys, identified by a shared ancestor key. */
public class KillAllEntitiesReducer extends Reducer<Key<?>, Key<?>, Void> {

  private static final long serialVersionUID = 7939357855356876000L;

  private static final int BATCH_SIZE = 100;

  @Override
  public void reduce(Key<?> ancestor, final ReducerInput<Key<?>> keysToDelete) {
    Iterator<List<Key<?>>> batches = partition(keysToDelete, BATCH_SIZE);
    while (batches.hasNext()) {
      final List<Key<?>> batch = batches.next();
      // Use a transaction to get retrying for free.
      tm().transact(() -> ofy().deleteWithoutBackup().keys(batch));
      getContext().incrementCounter("entities deleted", batch.size());
      for (Key<?> key : batch) {
        getContext().incrementCounter(String.format("%s deleted", key.getKind()));
      }
    }
  }
}
