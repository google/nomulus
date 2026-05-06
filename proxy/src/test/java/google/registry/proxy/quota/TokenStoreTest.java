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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

  private Instant assertTake(int grantAmount, int amountLeft, Instant timestamp) {
    return assertTake(user, grantAmount, amountLeft, timestamp);
  }

  private Instant assertTake(String user, int grantAmount, int amountLeft, Instant timestamp) {
    TimestampedInteger grantedToken = tokenStore.take(user);
    assertThat(grantedToken).isEqualTo(TimestampedInteger.create(grantAmount, timestamp));
    assertThat(tokenStore.getTokenForTests(user))
        .isEqualTo(TimestampedInteger.create(amountLeft, timestamp));
    return grantedToken.timestamp();
  }

  private void assertPut(
      Instant returnedTokenRefillTime, int amountAfterReturn, Instant refillTime) {
    assertPut(user, returnedTokenRefillTime, amountAfterReturn, refillTime);
  }

  private void assertPut(
      String user, Instant returnedTokenRefillTime, int amountAfterReturn, Instant refillTime) {
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
    when(quotaConfig.getRefreshPeriod()).thenReturn(Duration.ofSeconds(60));
    when(quotaConfig.getRefillPeriod(user)).thenReturn(Duration.ofSeconds(10));
    when(quotaConfig.getTokenAmount(user)).thenReturn(3);
    when(quotaConfig.getRefillPeriod(otherUser)).thenReturn(Duration.ofSeconds(15));
    when(quotaConfig.getTokenAmount(otherUser)).thenReturn(5);
  }

  @Test
  void testSuccess_take() {
    // Take 3 tokens one by one.
    Instant refillTime = clock.now();
    assertTake(1, 2, refillTime);
    assertTake(1, 1, refillTime);
    clock.advanceBy(Duration.ofSeconds(2));
    assertTake(1, 0, refillTime);

    // Take 1 token, not enough tokens left.
    clock.advanceBy(Duration.ofSeconds(3));
    assertTake(0, 0, refillTime);

    // Refill period passed. Take 1 token - success.
    clock.advanceBy(Duration.ofSeconds(6));
    refillTime = clock.now();
    assertTake(1, 2, refillTime);
  }

  @Test
  void testSuccess_put_entryDoesNotExist() {
    tokenStore.put(user, clock.now());
    assertThat(tokenStore.getTokenForTests(user)).isNull();
  }

  @Test
  void testSuccess_put() {
    Instant refillTime = clock.now();

    // Initialize the entry.
    Instant grantedTokenRefillTime = assertTake(1, 2, refillTime);

    // Put into full bucket.
    assertPut(grantedTokenRefillTime, 3, refillTime);
    assertPut(grantedTokenRefillTime, 3, refillTime);

    clock.advanceBy(Duration.ofSeconds(3));

    // Take 1 token out, put 1 back in.
    assertTake(1, 2, refillTime);
    assertPut(refillTime, 3, refillTime);

    // Do not put old token back.
    grantedTokenRefillTime = assertTake(1, 2, refillTime);
    clock.advanceBy(Duration.ofSeconds(11));
    refillTime = clock.now();
    assertPut(grantedTokenRefillTime, 3, refillTime);
  }

  @Test
  void testSuccess_takeAndPut() {
    Instant refillTime = clock.now();

    // Take 1 token.
    Instant grantedTokenRefillTime1 = assertTake(1, 2, refillTime);

    // Take 1 token.
    Instant grantedTokenRefillTime2 = assertTake(1, 1, refillTime);

    // Return first token.
    clock.advanceBy(Duration.ofSeconds(2));
    assertPut(grantedTokenRefillTime1, 2, refillTime);

    // Refill time passed, second returned token discarded.
    clock.advanceBy(Duration.ofSeconds(10));
    refillTime = clock.now();
    assertPut(grantedTokenRefillTime2, 3, refillTime);
  }

  @Test
  void testSuccess_multipleUsers() {
    Instant refillTime1 = clock.now();
    Instant refillTime2 = clock.now();

    // Take 1 from first user.
    Instant grantedTokenRefillTime1 = assertTake(user, 1, 2, refillTime1);

    // Take 1 from second user.
    Instant grantedTokenRefillTime2 = assertTake(otherUser, 1, 4, refillTime2);
    assertTake(otherUser, 1, 3, refillTime2);
    assertTake(otherUser, 1, 2, refillTime2);

    // first user tokens refilled.
    clock.advanceBy(Duration.ofSeconds(10));
    refillTime1 = clock.now();
    Instant grantedTokenRefillTime3 = assertTake(user, 1, 2, refillTime1);
    Instant grantedTokenRefillTime4 = assertTake(otherUser, 1, 1, refillTime2);
    assertPut(user, grantedTokenRefillTime1, 2, refillTime1);
    assertPut(otherUser, grantedTokenRefillTime2, 2, refillTime2);

    // second user tokens refilled.
    clock.advanceBy(Duration.ofSeconds(5));
    refillTime2 = clock.now();
    assertPut(user, grantedTokenRefillTime3, 3, refillTime1);
    assertPut(otherUser, grantedTokenRefillTime4, 5, refillTime2);
  }

  @Test
  void testSuccess_refresh() {
    Instant refillTime1 = clock.now();
    assertTake(user, 1, 2, refillTime1);

    clock.advanceBy(Duration.ofSeconds(5));
    Instant refillTime2 = clock.now();
    assertTake(otherUser, 1, 4, refillTime2);

    clock.advanceBy(Duration.ofSeconds(55));

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
      assertTake(1, SENTINEL_UNLIMITED_TOKENS, clock.now());
    }
    for (int i = 0; i < 10000; ++i) {
      assertPut(clock.now(), SENTINEL_UNLIMITED_TOKENS, clock.now());
    }
  }

  @Test
  void testSuccess_noRefill() {
    when(quotaConfig.getRefillPeriod(user)).thenReturn(Duration.ZERO);
    Instant refillTime = clock.now();
    assertTake(1, 2, refillTime);
    assertTake(1, 1, refillTime);
    assertTake(1, 0, refillTime);
    clock.advanceBy(Duration.ofDays(365));
    assertTake(0, 0, refillTime);
  }

  @Test
  void testSuccess_noRefresh() {
    when(quotaConfig.getRefreshPeriod()).thenReturn(Duration.ZERO);
    Instant refillTime = clock.now();
    assertTake(1, 2, refillTime);
    clock.advanceBy(Duration.ofDays(365));
    assertThat(tokenStore.getTokenForTests(user))
        .isEqualTo(TimestampedInteger.create(2, refillTime));
  }

  @Test
  void testSuccess_concurrency() throws Exception {
    ExecutorService executor = Executors.newWorkStealingPool();
    final Instant time1 = clock.now();
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
    clock.advanceBy(Duration.ofSeconds(5));
    submitAndWaitForTasks(
        executor, () -> tokenStore.take(user), () -> tokenStore.put(otherUser, time1));
    assertThat(tokenStore.getTokenForTests(user)).isEqualTo(TimestampedInteger.create(0, time1));
    assertThat(tokenStore.getTokenForTests(otherUser))
        .isEqualTo(TimestampedInteger.create(4, time1));

    // First user refill.
    clock.advanceBy(Duration.ofSeconds(5));
    final Instant time2 = clock.now();
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
    clock.advanceBy(Duration.ofSeconds(5));
    final Instant time3 = clock.now();
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
    when(quotaConfig.getRefreshPeriod()).thenReturn(Duration.ofSeconds(5));

    tokenStore.scheduleRefresh();

    // Verify that a task is scheduled.
    ArgumentCaptor<Runnable> argument = ArgumentCaptor.forClass(Runnable.class);
    verify(refreshExecutor)
        .scheduleWithFixedDelay(argument.capture(), eq(5L), eq(5L), eq(TimeUnit.SECONDS));

    // Verify that the scheduled task calls TokenStore.refresh().
    argument.getValue().run();
    verify(tokenStore).refresh();
  }
}
