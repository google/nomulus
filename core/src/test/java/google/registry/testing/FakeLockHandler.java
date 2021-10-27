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

package google.registry.testing;

import static com.google.common.base.Throwables.throwIfUnchecked;

import google.registry.request.lock.LockHandler;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import org.joda.time.Duration;

/** A fake {@link LockHandler} where user can control if lock acquisition succeeds. */
public class FakeLockHandler implements LockHandler {

  private static final long serialVersionUID = 6437880915118738492L;

  private final boolean lockSucceeds;

  /**
   * @param lockSucceeds if true - the lock acquisition will succeed and the callable will be
   *     called. If false, lock acquisition will fail and the caller isn't called.
   */
  public FakeLockHandler(boolean lockSucceeds) {
    this.lockSucceeds = lockSucceeds;
  }

  @Override
  public boolean executeWithLocks(
      Callable<Void> callable, @Nullable String tld, Duration leaseLength, String... lockNames) {
    return execute(callable);
  }

  @Override
  public boolean executeWithSqlLocks(
      Callable<Void> callable, @Nullable String tld, Duration leaseLength, String... lockNames) {
    return execute(callable);
  }

  private boolean execute(Callable<Void> callable) {
    if (!lockSucceeds) {
      return false;
    }

    try {
      callable.call();
    } catch (Exception e) {
      throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
    return true;
  }
}
