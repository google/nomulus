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

import static google.registry.proxy.quota.QuotaConfig.SENTINEL_UNLIMITED_TOKENS;
import static java.lang.StrictMath.max;
import static java.lang.StrictMath.min;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import google.registry.util.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.ThreadSafe;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * A thread-safe token store that supports concurrent {@link #take}, {@link #put}, and {@link
 * #refresh} operations.
 *
 * <p>The tokens represent quota allocated to each user, which needs to be leased to the user upon
 * connection and optionally returned to the store upon termination. Failure to acquire tokens
 * results in quota fulfillment failure, leading to automatic connection termination. For details on
 * tokens, see {@code config/default-config.yaml}.
 *
 * <p>The store also lazily refills tokens for a {@code userId} when a {@link #take} or a {@link
 * #put} takes place. It also exposes a {@link #refresh} method that goes through each entry in the
 * store and purges stale entries, in order to prevent the token store from growing too large.
 *
 * <p>There should be one token store for each protocol.
 */
@ThreadSafe
public class TokenStore {

  /** Value class representing a timestamped integer. */
  record TimestampedInteger(int value, DateTime timestamp) {

    static TimestampedInteger create(int value, DateTime timestamp) {
      return new TimestampedInteger(value, timestamp);
    }
  }

  /**
   * A wrapper to get around Java lambda's closure limitation.
   *
   * <p>Use the class to modify the value of a local variable captured by an lambda.
   */
  private static class Wrapper<T> {
    T value;
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** A map of {@code userId} to available tokens, timestamped at last refill time. */
  private final ConcurrentHashMap<String, TimestampedInteger> tokensMap = new ConcurrentHashMap<>();

  private final QuotaConfig config;
  private final ScheduledExecutorService refreshExecutor;
  private final Clock clock;

  public TokenStore(QuotaConfig config, ScheduledExecutorService refreshExecutor, Clock clock) {
    this.config = config;
    this.refreshExecutor = refreshExecutor;
    this.clock = clock;
  }

  /**
   * Attempts to take one token from the token store.
   *
   * <p>This method first check if the user already has an existing entry in the tokens map, and if
   * that entry has been last refilled before the refill period. In either case it will reset the
   * token amount to the allotted to the user.
   *
   * <p>The request can be partially fulfilled or all-or-nothing, meaning if there are fewer tokens
   * available than requested, we can grant all available ones, or grant nothing, depending on the
   * {@code partialGrant} parameter.
   *
   * @param userId the identifier of the user requesting the token.
   * @return the number of token granted, timestamped at refill time of the pool of tokens from
   *     which the granted one is taken.
   */
  TimestampedInteger take(String userId) {
    Wrapper<TimestampedInteger> grantedToken = new Wrapper<>();
    tokensMap.compute(
        userId,
        (user, availableTokens) -> {
          DateTime now = clock.nowUtc();
          int currentTokenCount;
          DateTime refillTime;
          // Checks if the user is provisioned with unlimited tokens.
          if (config.hasUnlimitedTokens(user)) {
            grantedToken.value = TimestampedInteger.create(1, now);
            return TimestampedInteger.create(SENTINEL_UNLIMITED_TOKENS, now);
          }
          // Checks if the entry exists.
          if (availableTokens == null
              // Or if refill is enabled and the entry needs to be refilled.
              || (!config.getRefillPeriod(user).isEqual(Duration.ZERO)
                  && !new Duration(availableTokens.timestamp(), now)
                      .isShorterThan(config.getRefillPeriod(user)))) {
            currentTokenCount = config.getTokenAmount(user);
            refillTime = now;
          } else {
            currentTokenCount = availableTokens.value();
            refillTime = availableTokens.timestamp();
          }
          int newTokenCount = max(0, currentTokenCount - 1);
          grantedToken.value =
              TimestampedInteger.create(currentTokenCount - newTokenCount, refillTime);
          return TimestampedInteger.create(newTokenCount, refillTime);
        });
    return grantedToken.value;
  }

  /**
   * Attempts to return the granted token to the token store.
   *
   * <p>The method first check if a refill is needed, and do it accordingly. It then checks if the
   * returned token are from the current pool (i. e. has the same refill timestamp as the current
   * pool), and returns the token, capped at the allotted amount for the {@code userId}.
   *
   * @param userId the identifier of the user returning the token.
   * @param returnedTokenRefillTime The refill time of the pool of tokens from which the returned
   *     one is taken from.
   */
  void put(String userId, DateTime returnedTokenRefillTime) {
    tokensMap.computeIfPresent(
        userId,
        (user, availableTokens) -> {
          DateTime now = clock.nowUtc();
          int currentTokenCount = availableTokens.value();
          DateTime refillTime = availableTokens.timestamp();
          int newTokenCount;
          // Check if quota is unlimited.
          if (!config.hasUnlimitedTokens(userId)) {
            // Check if refill is enabled and a refill is needed.
            if (!config.getRefillPeriod(user).isEqual(Duration.ZERO)
                && !new Duration(availableTokens.timestamp(), now)
                    .isShorterThan(config.getRefillPeriod(user))) {
              currentTokenCount = config.getTokenAmount(user);
              refillTime = now;
            }
            // If the returned token comes from the current pool, add it back, otherwise discard it.
            newTokenCount =
                returnedTokenRefillTime.equals(refillTime)
                    ? min(currentTokenCount + 1, config.getTokenAmount(userId))
                    : currentTokenCount;
          } else {
            newTokenCount = SENTINEL_UNLIMITED_TOKENS;
          }
          return TimestampedInteger.create(newTokenCount, refillTime);
        });
  }

  /**
   * Refreshes the token store and deletes any entry that has not been refilled for longer than the
   * refresh period.
   *
   * <p>Strictly speaking it should delete the entries that have not been updated (put, taken,
   * refill) for longer than the refresh period. But the last update time is not recorded. Typically
   * the refill period is much shorter than the refresh period, so the last refill time should serve
   * as a good proxy for last update time as the actual update time cannot be one refill period
   * later from the refill time, otherwise another refill would have been performed.
   */
  void refresh() {
    tokensMap.forEach(
        (user, availableTokens) -> {
          if (!new Duration(availableTokens.timestamp(), clock.nowUtc())
              .isShorterThan(config.getRefreshPeriod())) {
            tokensMap.remove(user);
          }
        });
  }

  /** Schedules token store refresh if enabled. */
  void scheduleRefresh() {
    // Only schedule refresh if the refresh period is not zero.
    if (!config.getRefreshPeriod().isEqual(Duration.ZERO)) {
      Future<?> unusedFuture =
          refreshExecutor.scheduleWithFixedDelay(
              () -> {
                refresh();
                logger.atInfo().log("Refreshing quota for protocol %s", config.getProtocolName());
              },
              config.getRefreshPeriod().getStandardSeconds(),
              config.getRefreshPeriod().getStandardSeconds(),
              TimeUnit.SECONDS);
    }
  }

  /**
   * Helper method to retrieve the timestamped token value for a {@code userId} for testing.
   *
   * <p>This non-mutating method is exposed solely for testing, so that the {@link #tokensMap} can
   * stay private and not be altered unintentionally.
   */
  @VisibleForTesting
  TimestampedInteger getTokenForTests(String userId) {
    return tokensMap.get(userId);
  }
}
