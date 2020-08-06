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
import static google.registry.proxy.quota.QuotaConfig.SENTINEL_UNLIMITED_TOKENS;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.proxy.quota.TokenStore.TimestampedInteger;
import google.registry.testing.FakeClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link TokenStore}. */
class TokenStoreTest {

  private final QuotaConfig quotaConfig = mock(QuotaConfig.class);
  private final FakeClock clock = new FakeClock();
  private final ScheduledExecutorService refreshExecutor = mock(ScheduledExecutorService.class);
  private final TokenStore tokenStore = spy(new TokenStore(quotaConfig, refreshExecutor, clock));
  private final String user = "theUser";
  private final String otherUser = "theOtherUser";

  private DateTime assertTake(int grantAmount, int amountLeft, DateTime timestamp) {
    return assertTake(user, grantAmount, amountLeft, timestamp);
  }

  private DateTime assertTake(String user, int grantAmount, int amountLeft, DateTime timestamp) {
    TimestampedInteger grantedToken = tokenStore.take(user);
    assertThat(grantedToken).isEqualTo(TimestampedInteger.create(grantAmount, timestamp));
    assertThat(tokenStore.getTokenForTests(user))
        .isEqualTo(TimestampedInteger.create(amountLeft, timestamp));
    return grantedToken.timestamp();
  }

  private void assertPut(
      DateTime returnedTokenRefillTime, int amountAfterReturn, DateTime refillTime) {
    assertPut(user, returnedTokenRefillTime, amountAfterReturn, refillTime);
  }

  private void assertPut(
      String user, DateTime returnedTokenRefillTime, int amountAfterReturn, DateTime refillTime) {
    tokenStore.put(user, returnedTokenRefillTime);
    assertThat(tokenStore.getTokenForTests(user))
        .isEqualTo(TimestampedInteger.create(amountAfterReturn, refillTime));
  }

  private void submitAndWaitForTasks(ExecutorService executor, Runnable... tasks) {
    List<Future<?>> futures = new ArrayList<>();
    for (Runnable task : tasks) {
      futures.add(executor.submit(task));
    }
    futures.forEach(
        f -> {
          try {
            f.get();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @BeforeEach
  void beforeEach() {
    when(quotaConfig.getRefreshPeriod()).thenReturn(Duration.standardSeconds(60));
    when(quotaConfig.getRefillPeriod(user)).thenReturn(Duration.standardSeconds(10));
    when(quotaConfig.getTokenAmount(user)).thenReturn(3);
    when(quotaConfig.getRefillPeriod(otherUser)).thenReturn(Duration.standardSeconds(15));
    when(quotaConfig.getTokenAmount(otherUser)).thenReturn(5);
  }

  @Test
  void testSuccess_take() {
    // Take 3 tokens one by one.
    DateTime refillTime = clock.nowUtc();
    assertTake(1, 2, refillTime);
    assertTake(1, 1, refillTime);
    clock.advanceBy(Duration.standardSeconds(2));
    assertTake(1, 0, refillTime);

    // Take 1 token, not enough tokens left.
    clock.advanceBy(Duration.standardSeconds(3));
    assertTake(0, 0, refillTime);

    // Refill period passed. Take 1 token - success.
    clock.advanceBy(Duration.standardSeconds(6));
    refillTime = clock.nowUtc();
    assertTake(1, 2, refillTime);
  }

  @Test
  void testSuccess_put_entryDoesNotExist() {
    tokenStore.put(user, clock.nowUtc());
    assertThat(tokenStore.getTokenForTests(user)).isNull();
  }

  @Test
  void testSuccess_put() {
    DateTime refillTime = clock.nowUtc();

    // Initialize the entry.
    DateTime grantedTokenRefillTime = assertTake(1, 2, refillTime);

    // Put into full bucket.
    assertPut(grantedTokenRefillTime, 3, refillTime);
    assertPut(grantedTokenRefillTime, 3, refillTime);

    clock.advanceBy(Duration.standardSeconds(3));

    // Take 1 token out, put 1 back in.
    assertTake(1, 2, refillTime);
    assertPut(refillTime, 3, refillTime);

    // Do not put old token back.
    grantedTokenRefillTime = assertTake(1, 2, refillTime);
    clock.advanceBy(Duration.standardSeconds(11));
    refillTime = clock.nowUtc();
    assertPut(grantedTokenRefillTime, 3, refillTime);
  }

  @Test
  void testSuccess_takeAndPut() {
    DateTime refillTime = clock.nowUtc();

    // Take 1 token.
    DateTime grantedTokenRefillTime1 = assertTake(1, 2, refillTime);

    // Take 1 token.
    DateTime grantedTokenRefillTime2 = assertTake(1, 1, refillTime);

    // Return first token.
    clock.advanceBy(Duration.standardSeconds(2));
    assertPut(grantedTokenRefillTime1, 2, refillTime);

    // Refill time passed, second returned token discarded.
    clock.advanceBy(Duration.standardSeconds(10));
    refillTime = clock.nowUtc();
    assertPut(grantedTokenRefillTime2, 3, refillTime);
  }

  @Test
  void testSuccess_multipleUsers() {
    DateTime refillTime1 = clock.nowUtc();
    DateTime refillTime2 = clock.nowUtc();

    // Take 1 from first user.
    DateTime grantedTokenRefillTime1 = assertTake(user, 1, 2, refillTime1);

    // Take 1 from second user.
    DateTime grantedTokenRefillTime2 = assertTake(otherUser, 1, 4, refillTime2);
    assertTake(otherUser, 1, 3, refillTime2);
    assertTake(otherUser, 1, 2, refillTime2);

    // first user tokens refilled.
    clock.advanceBy(Duration.standardSeconds(10));
    refillTime1 = clock.nowUtc();
    DateTime grantedTokenRefillTime3 = assertTake(user, 1, 2, refillTime1);
    DateTime grantedTokenRefillTime4 = assertTake(otherUser, 1, 1, refillTime2);
    assertPut(user, grantedTokenRefillTime1, 2, refillTime1);
    assertPut(otherUser, grantedTokenRefillTime2, 2, refillTime2);

    // second user tokens refilled.
    clock.advanceBy(Duration.standardSeconds(5));
    refillTime2 = clock.nowUtc();
    assertPut(user, grantedTokenRefillTime3, 3, refillTime1);
    assertPut(otherUser, grantedTokenRefillTime4, 5, refillTime2);
  }

  @Test
  void testSuccess_refresh() {
    DateTime refillTime1 = clock.nowUtc();
    assertTake(user, 1, 2, refillTime1);

    clock.advanceBy(Duration.standardSeconds(5));
    DateTime refillTime2 = clock.nowUtc();
    assertTake(otherUser, 1, 4, refillTime2);

    clock.advanceBy(Duration.standardSeconds(55));

    // Entry for user is 60s old, entry for otherUser is 55s old.
    tokenStore.refresh();
    assertThat(tokenStore.getTokenForTests(user)).isNull();
    assertThat(tokenStore.getTokenForTests(otherUser))
        .isEqualTo(TimestampedInteger.create(4, refillTime2));
  }

  @Test
  void testSuccess_unlimitedQuota() {
    when(quotaConfig.hasUnlimitedTokens(user)).thenReturn(true);
    for (int i = 0; i < 10000; ++i) {
      assertTake(1, SENTINEL_UNLIMITED_TOKENS, clock.nowUtc());
    }
    for (int i = 0; i < 10000; ++i) {
      assertPut(clock.nowUtc(), SENTINEL_UNLIMITED_TOKENS, clock.nowUtc());
    }
  }

  @Test
  void testSuccess_noRefill() {
    when(quotaConfig.getRefillPeriod(user)).thenReturn(Duration.ZERO);
    DateTime refillTime = clock.nowUtc();
    assertTake(1, 2, refillTime);
    assertTake(1, 1, refillTime);
    assertTake(1, 0, refillTime);
    clock.advanceBy(Duration.standardDays(365));
    assertTake(0, 0, refillTime);
  }

  @Test
  void testSuccess_noRefresh() {
    when(quotaConfig.getRefreshPeriod()).thenReturn(Duration.ZERO);
    DateTime refillTime = clock.nowUtc();
    assertTake(1, 2, refillTime);
    clock.advanceBy(Duration.standardDays(365));
    assertThat(tokenStore.getTokenForTests(user))
        .isEqualTo(TimestampedInteger.create(2, refillTime));
  }

  @Test
  void testSuccess_concurrency() throws Exception {
    ExecutorService executor = Executors.newWorkStealingPool();
    final DateTime time1 = clock.nowUtc();
    submitAndWaitForTasks(
        executor,
        () -> tokenStore.take(user),
        () -> tokenStore.take(otherUser),
        () -> tokenStore.take(user),
        () -> tokenStore.take(otherUser));
    assertThat(tokenStore.getTokenForTests(user)).isEqualTo(TimestampedInteger.create(1, time1));
    assertThat(tokenStore.getTokenForTests(otherUser))
        .isEqualTo(TimestampedInteger.create(3, time1));

    // No refill.
    clock.advanceBy(Duration.standardSeconds(5));
    submitAndWaitForTasks(
        executor, () -> tokenStore.take(user), () -> tokenStore.put(otherUser, time1));
    assertThat(tokenStore.getTokenForTests(user)).isEqualTo(TimestampedInteger.create(0, time1));
    assertThat(tokenStore.getTokenForTests(otherUser))
        .isEqualTo(TimestampedInteger.create(4, time1));

    // First user refill.
    clock.advanceBy(Duration.standardSeconds(5));
    final DateTime time2 = clock.nowUtc();
    submitAndWaitForTasks(
        executor,
        () -> {
          tokenStore.put(user, time1);
          tokenStore.take(user);
        },
        () -> tokenStore.take(otherUser));
    assertThat(tokenStore.getTokenForTests(user)).isEqualTo(TimestampedInteger.create(2, time2));
    assertThat(tokenStore.getTokenForTests(otherUser))
        .isEqualTo(TimestampedInteger.create(3, time1));

    // Second user refill.
    clock.advanceBy(Duration.standardSeconds(5));
    final DateTime time3 = clock.nowUtc();
    submitAndWaitForTasks(
        executor,
        () -> tokenStore.take(user),
        () -> {
          tokenStore.put(otherUser, time1);
          tokenStore.take(otherUser);
        });
    assertThat(tokenStore.getTokenForTests(user)).isEqualTo(TimestampedInteger.create(1, time2));
    assertThat(tokenStore.getTokenForTests(otherUser))
        .isEqualTo(TimestampedInteger.create(4, time3));
  }

  @Test
  void testSuccess_scheduleRefresh() throws Exception {
    when(quotaConfig.getRefreshPeriod()).thenReturn(Duration.standardSeconds(5));

    tokenStore.scheduleRefresh();

    // Verify that a task is scheduled.
    ArgumentCaptor<Runnable> argument = ArgumentCaptor.forClass(Runnable.class);
    verify(refreshExecutor)
        .scheduleWithFixedDelay(
            argument.capture(), eq((long) 5), eq((long) 5), eq(TimeUnit.SECONDS));

    // Verify that the scheduled task calls TokenStore.refresh().
    argument.getValue().run();
    verify(tokenStore).refresh();
  }
}
