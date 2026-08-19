// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.quota;

import java.time.Duration;

/** Interface for managing quota. */
public interface QuotaManager {
  /** Attempts to acquire a token (out of the given max amount) with the given TTL. */
  boolean acquireQuota(String id, int maxTokenAmount, Duration expirationDuration);

  /** Refreshes the TTL of an existing token. */
  void refreshQuota(String id, Duration expirationDuration);

  /** Returns a token to the pool (possibly useful for connection throttling). */
  void releaseQuota(String id, int maxTokenAmount);
}
