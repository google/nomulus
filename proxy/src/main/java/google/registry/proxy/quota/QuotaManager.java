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

package google.registry.proxy.quota;

import google.registry.proxy.quota.TokenStore.TimestampedInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import javax.annotation.concurrent.ThreadSafe;
import org.joda.time.DateTime;

/**
 * A thread-safe quota manager that schedules background refresh if necessary.
 *
 * <p>This class abstracts away details about the {@link TokenStore}. It:
 *
 * <ul>
 *   <li>Translates a {@link QuotaRequest} to taking one token from the store, blocks the caller,
 *       and responds with a {@link QuotaResponse}.
 *   <li>Translates a {@link QuotaRebate} to putting the token to the store asynchronously, and
 *       immediately returns.
 *   <li>Periodically refreshes the token records asynchronously to purge stale recodes.
 * </ul>
 *
 * <p>There should be one {@link QuotaManager} per protocol.
 */
@ThreadSafe
public class QuotaManager {

  /** Value class representing a quota request. */
  public record QuotaRequest(String userId) {}

  /** Value class representing a quota response. */
  public record QuotaResponse(boolean success, String userId, DateTime grantedTokenRefillTime) {}

  /** Value class representing a quota rebate. */
  public record QuotaRebate(String userId, DateTime grantedTokenRefillTime) {
    public static QuotaRebate create(QuotaResponse response) {
      return new QuotaRebate(response.userId(), response.grantedTokenRefillTime());
    }
  }

  private final TokenStore tokenStore;

  private final ExecutorService backgroundExecutor;

  public QuotaManager(TokenStore tokenStore, ExecutorService backgroundExecutor) {
    this.tokenStore = tokenStore;
    this.backgroundExecutor = backgroundExecutor;
    tokenStore.scheduleRefresh();
  }

  /** Attempts to acquire requested quota, synchronously. */
  public QuotaResponse acquireQuota(QuotaRequest request) {
    TimestampedInteger tokens = tokenStore.take(request.userId());
    return new QuotaResponse(tokens.value() != 0, request.userId(), tokens.timestamp());
  }

  /** Returns granted quota to the token store, asynchronously. */
  public Future<?> releaseQuota(QuotaRebate rebate) {
    return backgroundExecutor.submit(
        () -> tokenStore.put(rebate.userId(), rebate.grantedTokenRefillTime()));
  }
}
