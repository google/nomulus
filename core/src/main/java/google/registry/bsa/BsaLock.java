// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.bsa;

import google.registry.config.RegistryConfig.Config;
import google.registry.request.lock.LockHandler;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.joda.time.Duration;

/** Helper for guarding all BSA related work with a common lock. */
public class BsaLock {

  private static final String LOCK_NAME = "all-bsa-jobs";

  private final LockHandler lockHandler;
  private final Duration leaseExpiry;

  @Inject
  BsaLock(LockHandler lockHandler, @Config("bsaLockLeaseExpiry") Duration leaseExpiry) {
    this.lockHandler = lockHandler;
    this.leaseExpiry = leaseExpiry;
  }

  boolean executeWithLock(Callable<Void> callable) {
    return lockHandler.executeWithLocks(callable, null, leaseExpiry, LOCK_NAME);
  }
}
