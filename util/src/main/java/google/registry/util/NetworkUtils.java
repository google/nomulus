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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.CollectionUtils.union;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import javax.annotation.concurrent.GuardedBy;

/** Utilities for networking. */
public final class NetworkUtils {

  private static final int PICK_ATTEMPTS = 16;
  private static final int RANDOM_PORT_BASE = 32768;
  private static final int RANDOM_PORT_RANGE = 60000 - RANDOM_PORT_BASE + 1;

  @GuardedBy("random")
  private static final Random random = new SecureRandom();

  /**
   * Returns random unused local port that can be used for TCP listening server.
   *
   * @throws RuntimeException if failed to find free port after {@value #PICK_ATTEMPTS} attempts
   */
  public static int pickUnusedPort() {
    // In an ideal world, we would just listen on port 0 and use whatever random port the kernel
    // assigns us. But our CI testing system reports there are rare circumstances in which this
    // doesn't work.
    Iterator<Integer> ports = union(generateRandomPorts(PICK_ATTEMPTS), 0).iterator();
    while (true) {
      try (ServerSocket serverSocket = new ServerSocket(ports.next())) {
        return serverSocket.getLocalPort();
      } catch (IOException e) {
        if (!ports.hasNext()) {
          throw new RuntimeException("Failed to acquire random port", e);
        }
      }
    }
  }

  /**
   * Returns the fully-qualified domain name of the local host in all lower case.
   *
   * @throws RuntimeException to wrap {@link UnknownHostException} if the local host could not be
   *     resolved into an address
   */
  public static String getCanonicalHostName() {
    try {
      return Ascii.toLowerCase(getExternalAddressOfLocalSystem().getCanonicalHostName());
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the externally-facing IPv4 network address of the local host.
   *
   * <p>This function implements a workaround for an <a
   * href="http://bugs.java.com/bugdatabase/view_bug.do?bug_id=4665037">issue</a> in {@link
   * InetAddress#getLocalHost}.
   *
   * <p><b>Note:</b> This code was pilfered from {@code com.google.net.base.LocalHost} which was
   * never made open source.
   *
   * @throws UnknownHostException if the local host could not be resolved into an address
   */
  public static InetAddress getExternalAddressOfLocalSystem() throws UnknownHostException {
    InetAddress localhost = InetAddress.getLocalHost();
    // If we have a loopback address, look for an address using the network cards.
    if (localhost.isLoopbackAddress()) {
      try {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
          return localhost;
        }
        while (interfaces.hasMoreElements()) {
          NetworkInterface networkInterface = interfaces.nextElement();
          Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
          while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (!(address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address instanceof Inet6Address)) {
              return address;
            }
          }
        }
      } catch (SocketException e) {
        // Fall-through.
      }
    }
    return localhost;
  }

  private static ImmutableSet<Integer> generateRandomPorts(int count) {
    checkArgument(count > 0);
    Set<Integer> result = new HashSet<>();
    synchronized (random) {
      while (result.size() < count) {
        result.add(RANDOM_PORT_BASE + random.nextInt(RANDOM_PORT_RANGE));
      }
    }
    return ImmutableSet.copyOf(result);
  }

  private NetworkUtils() {}
}
