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

import google.registry.util.Clock;
import java.util.function.Supplier;

/** Wrapper for {@link Supplier} that disallows mutations and fails the transaction at the end. */
class ReadOnlyWork<R> extends CommitLoggedWork<R> {

  ReadOnlyWork(Supplier<R> work, Clock clock) {
    super(work, clock);
  }

  @Override
  protected TransactionInfo createNewTransactionInfo() {
    return super.createNewTransactionInfo().setReadOnly();
  }

  @Override
  public void run() {
    super.run();
    throw new KillTransactionException();
  }

  /** Exception used to exit a transaction. */
  static class KillTransactionException extends RuntimeException {}
}
