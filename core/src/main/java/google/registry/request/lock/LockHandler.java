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

package google.registry.request.lock;

import java.io.Serializable;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import org.joda.time.Duration;

/**
 * Code execution locked on some shared resource.
 *
 * <p>Locks are either specific to a tld or global to the entire system, in which case a tld of null
 * is used.
 */
public interface LockHandler extends Serializable {

  /**
   * Acquire one or more locks and execute a Void {@link Callable}.
   *
   * <p>Runs on a thread that will be killed if it doesn't complete before the lease expires.
   *
   * <p>Note that locks are specific either to a given tld or to the entire system (in which case
   * tld should be passed as null).
   *
   * @return true if all locks were acquired and the callable was run; false otherwise.
   */
  boolean executeWithLocks(
      final Callable<Void> callable,
      @Nullable String tld,
      Duration leaseLength,
      String... lockNames);

  /**
   * Acquire one or more locks using only Cloud SQL and execute a Void {@link Callable}.
   *
   * <p>Runs on a thread that will be killed if it doesn't complete before the lease expires.
   *
   * <p>Note that locks are specific either to a given tld or to the entire system (in which case
   * tld should be passed as null).
   *
   * <p>This method exists so that Beam pipelines can acquire / load / release locks.
   *
   * @return true if all locks were acquired and the callable was run; false otherwise.
   */
  boolean executeWithSqlLocks(
      final Callable<Void> callable,
      @Nullable String tld,
      Duration leaseLength,
      String... lockNames);
}
