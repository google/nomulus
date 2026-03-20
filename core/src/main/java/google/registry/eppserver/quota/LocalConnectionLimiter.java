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
import javax.annotation.concurrent.ThreadSafe;

/**
 * Thread-safe, in-memory rate limiter for restricting the number of concurrent connections allowed
 * per IP address and per authenticated certificate.
 */
@ThreadSafe
@Singleton
public class LocalConnectionLimiter {

  private final int maxConnectionsPerIp;
  private final int maxConnectionsPerCert;

  private final ConcurrentHashMap<String, Integer> ipConnections = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Integer> certConnections = new ConcurrentHashMap<>();

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

  private boolean acquire(String key, ConcurrentHashMap<String, Integer> map, int limit) {
    boolean[] acquired = new boolean[1];
    map.compute(
        key,
        (k, v) -> {
          if (v == null) {
            acquired[0] = true;
            return 1;
          }
          if (v < limit) {
            acquired[0] = true;
            return v + 1;
          }
          acquired[0] = false;
          return v;
        });
    return acquired[0];
  }

  private void release(String key, ConcurrentHashMap<String, Integer> map) {
    map.computeIfPresent(
        key,
        (k, v) -> {
          if (v <= 1) {
            return null;
          }
          return v - 1;
        });
  }
}
