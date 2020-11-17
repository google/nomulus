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

import google.registry.config.RegistryEnvironment;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Implements simplified datastore to SQL transaction replay.
 *
 * <p>This code is to be removed when the actual replay cron job is implemented.
 */
public class ReplayQueue {

  static ConcurrentLinkedQueue<TransactionInfo> queue =
      new ConcurrentLinkedQueue<TransactionInfo>();

  static void addInTests(TransactionInfo info) {
    if (RegistryEnvironment.get() == RegistryEnvironment.UNITTEST) {
      queue.add(info);
    }
  }

  public static void replay() {
    TransactionInfo info;
    while ((info = queue.poll()) != null) {
      info.saveToJpa();
    }
  }

  public static void clear() {
    queue.clear();
  }
}
