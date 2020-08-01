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

package google.registry.proxy.quota;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.MoreExecutors;
import google.registry.proxy.quota.QuotaManager.QuotaRebate;
import google.registry.proxy.quota.QuotaManager.QuotaRequest;
import google.registry.proxy.quota.QuotaManager.QuotaResponse;
import google.registry.proxy.quota.TokenStore.TimestampedInteger;
import google.registry.testing.FakeClock;
import java.util.concurrent.Future;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link QuotaManager}. */
class QuotaManagerTest {

  private static final String USER_ID = "theUser";

  private final TokenStore tokenStore = mock(TokenStore.class);
  private final FakeClock clock = new FakeClock();

  private QuotaManager quotaManager =
      new QuotaManager(tokenStore, MoreExecutors.newDirectExecutorService());
  private QuotaRequest request;
  private QuotaResponse response;

  @Test
  void testSuccess_requestApproved() {
    when(tokenStore.take(anyString())).thenReturn(TimestampedInteger.create(1, clock.nowUtc()));

    request = QuotaRequest.create(USER_ID);
    response = quotaManager.acquireQuota(request);
    assertThat(response.success()).isTrue();
    assertThat(response.userId()).isEqualTo(USER_ID);
    assertThat(response.grantedTokenRefillTime()).isEqualTo(clock.nowUtc());
  }

  @Test
  void testSuccess_requestDenied() {
    when(tokenStore.take(anyString())).thenReturn(TimestampedInteger.create(0, clock.nowUtc()));

    request = QuotaRequest.create(USER_ID);
    response = quotaManager.acquireQuota(request);
    assertThat(response.success()).isFalse();
    assertThat(response.userId()).isEqualTo(USER_ID);
    assertThat(response.grantedTokenRefillTime()).isEqualTo(clock.nowUtc());
  }

  @Test
  void testSuccess_rebate() throws Exception {
    DateTime grantedTokenRefillTime = clock.nowUtc();
    response = QuotaResponse.create(true, USER_ID, grantedTokenRefillTime);
    QuotaRebate rebate = QuotaRebate.create(response);
    Future<?> unusedFuture = quotaManager.releaseQuota(rebate);
    verify(tokenStore).scheduleRefresh();
    verify(tokenStore).put(USER_ID, grantedTokenRefillTime);
    verifyNoMoreInteractions(tokenStore);
  }
}
