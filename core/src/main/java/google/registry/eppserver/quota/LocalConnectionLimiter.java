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

package google.registry.eppserver.quota;

import google.registry.config.RegistryConfig.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Thread-safe, in-memory rate limiter for restricting the number of concurrent connections
 * allowed per IP address and per authenticated certificate.
 */
@ThreadSafe
@Singleton
public class LocalConnectionLimiter {

  private final int maxConnectionsPerIp;
  private final int maxConnectionsPerCert;

  private final ConcurrentHashMap<String, AtomicInteger> ipConnections = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicInteger> certConnections =
      new ConcurrentHashMap<>();

  @Inject
  public LocalConnectionLimiter(
      @Config("eppServerMaxConnectionsPerIp") int maxConnectionsPerIp,
      @Config("eppServerMaxConnectionsPerCert") int maxConnectionsPerCert) {
    this.maxConnectionsPerIp = maxConnectionsPerIp;
    this.maxConnectionsPerCert = maxConnectionsPerCert;
  }

  /** Attempts to acquire a slot for the given IP address. */
  public boolean acquireIp(String ipAddress) {
    return acquire(ipAddress, ipConnections, maxConnectionsPerIp);
  }

  /** Releases a slot for the given IP address. */
  public void releaseIp(String ipAddress) {
    release(ipAddress, ipConnections);
  }

  /** Attempts to acquire a slot for the given certificate hash. */
  public boolean acquireCert(String certHash) {
    return acquire(certHash, certConnections, maxConnectionsPerCert);
  }

  /** Releases a slot for the given certificate hash. */
  public void releaseCert(String certHash) {
    release(certHash, certConnections);
  }

  private boolean acquire(String key, ConcurrentHashMap<String, AtomicInteger> map, int limit) {
    AtomicInteger count = map.computeIfAbsent(key, k -> new AtomicInteger(0));
    if (count.incrementAndGet() <= limit) {
      return true;
    }
    // Limit exceeded, decrement to revert the increment
    count.decrementAndGet();
    return false;
  }

  private void release(String key, ConcurrentHashMap<String, AtomicInteger> map) {
    AtomicInteger count = map.get(key);
    if (count != null && count.decrementAndGet() <= 0) {
      map.remove(key);
    }
  }
}
