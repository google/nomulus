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

public enum RefreshStage {
  /**
   * Checks for stale unblockable domains. The output is a stream of {@link
   * google.registry.bsa.api.UnblockableDomainChange} objects that describe the stale domains.
   */
  CHECK_FOR_CHANGES,
  /** Fixes the stale domains in the database. */
  APPLY_CHANGES,
  /** Reports the unblockable domains to be removed to BSA. */
  UPLOAD_REMOVALS,
  /** Reports the newly found unblockable domains to BSA. */
  UPLOAD_ADDITIONS,
  DONE;
}
