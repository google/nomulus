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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.AbstractSequentialIterator;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InetAddresses;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Iterator;
import javax.annotation.Nullable;

/**
 * Class representing an RFC 1519 CIDR IP address block.
 *
 * <p>When creating a CidrAddressBlock from an IP string literal
 * without a specified CIDR netmask (i.e. no trailing "/16" or "/64")
 * or an InetAddress with an accompanying integer netmask, then the
 * maximum length netmask for the address famiy of the specified
 * address is used (i.e. 32 for IPv4, 128 for IPv6).  I.e. "1.2.3.4"
 * is automatically treated as "1.2.3.4/32" and, similarly,
 * "2001:db8::1" is automatically treated as "2001:db8::1/128".
 *
 */
// TODO(b/21870796): Migrate to Guava version when this is open-sourced.
public class CidrAddressBlock implements Iterable<InetAddress>, Serializable {

  /**
   * Wrapper class around a logger instance for {@link CidrAddressBlock}.
   *
   * <p>We don't want to have a static instance of {@link Logger} in {@link CidrAddressBlock},
   * because that can cause a race condition, since the logging subsystem might not yet be
   * initialized. With this wrapper, the {@link Logger} will be initialized on first use.
   */
  static class CidrAddressBlockLogger {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  }

  private final InetAddress ip;

  /**
   * The number of block or mask bits needed to create the address block
   * (starting from the most-significant side of the address).
   */
  private final int netmask;

  /**
   * Attempts to parse the given String into a CIDR block.
   *
   * <p>If the string is an IP string literal without a specified
   * CIDR netmask (i.e. no trailing "/16" or "/64") then the maximum
   * length netmask for the address famiy of the specified address is
   * used (i.e. 32 for IPv4, 128 for IPv6).
   *
   * <p>The specified IP address portion must be properly truncated
   * (i.e. all the host bits must be zero) or the input is considered
   * malformed. For example, "1.2.3.0/24" is accepted but "1.2.3.4/24"
   * is not. Similarly, for IPv6, "2001:db8::/32" is accepted whereas
   * "2001:db8::1/32" is not.
   *
   * <p>If inputs might not be properly truncated but would be acceptable
   * to the application consider constructing a {@code CidrAddressBlock}
   * via {@code create()}.
   *
   * @param s a String of the form "217.68.0.0/16" or "2001:db8::/32".
   *
   * @throws IllegalArgumentException if s is malformed or does not
   *         represent a valid CIDR block.
   */
  public CidrAddressBlock(String s) {
    this(parseInetAddress(s), parseNetmask(s), false);
  }

  /**
   * Attempts to parse the given String and int into a CIDR block.
   *
   * <p>The specified IP address portion must be properly truncated
   * (i.e. all the host bits must be zero) or the input is considered
   * malformed. For example, "1.2.3.0/24" is accepted but "1.2.3.4/24"
   * is not. Similarly, for IPv6, "2001:db8::/32" is accepted whereas
   * "2001:db8::1/32" is not.
   *
   * <p>An IP address without a netmask will automatically have the
   * maximum applicable netmask for its address family.  I.e. "1.2.3.4"
   * is automatically treated as "1.2.3.4/32", and "2001:db8::1" is
   * automatically treated as "2001:db8::1/128".
   *
   * <p>If inputs might not be properly truncated but would be acceptable
   * to the application consider constructing a {@code CidrAddressBlock}
   * via {@code create()}.
   *
   * @param ip a String of the form "217.68.0.0" or "2001:db8::".
   * @param netmask an int between 0 and 32 (for IPv4) or 128 (for IPv6).
   *        This is the number of bits, starting from the big end of the IP,
   *        that will be used for network bits (as opposed to host bits)
   *        in this CIDR block.
   *
   * @throws IllegalArgumentException if the params are malformed or do not
   *         represent a valid CIDR block.
   */
  public CidrAddressBlock(String ip, int netmask) {
    this(InetAddresses.forString(ip), checkNotNegative(netmask), false);
  }

  public CidrAddressBlock(InetAddress ip) {
    this(ip, AUTO_NETMASK, false);
  }

  public CidrAddressBlock(InetAddress ip, int netmask) {
    this(ip, checkNotNegative(netmask), false);
  }

  /**
   * Attempts to construct a CIDR block from the IP address and netmask,
   * truncating the IP address as required.
   *
   * <p>The specified IP address portion need not be properly truncated
   * (i.e. all the host bits need not be zero); truncation will be silently
   * performed. For example, "1.2.3.4/24" is accepted and returns the
   * same {@code CidrAddressBlock} as "1.2.3.0/24". Similarly, for IPv6,
   * "2001:db8::1/32" is accepted and returns the same
   * {@code CidrAddressBlock} as "2001:db8::/32".
   *
   * @param ip {@link InetAddress}, possibly requiring truncation.
   * @param netmask an int between 0 and 32 (for IPv4) or 128 (for IPv6).
   *        This is the number of bits, starting from the big end of the IP,
   *        that will be used for network bits (as opposed to host bits)
   *        when truncating the supplied {@link InetAddress}.
   *
   * @throws IllegalArgumentException if the params are malformed or do not
   *         represent a valid CIDR block.
   * @throws NullPointerException if a parameter is null.
   */
  public static CidrAddressBlock create(InetAddress ip, int netmask) {
    return new CidrAddressBlock(ip, checkNotNegative(netmask), true);
  }

  /**
   * Attempts to construct a CIDR block from the IP address and netmask
   * expressed as a String, truncating the IP address as required.
   *
   * <p>The specified IP address portion need not be properly truncated
   * (i.e. all the host bits need not be zero); truncation will be silently
   * performed. For example, "1.2.3.4/24" is accepted and returns the
   * same {@code CidrAddressBlock} as "1.2.3.0/24". Similarly, for IPv6,
   * "2001:db8::1/32" is accepted and returns the same
   * {@code CidrAddressBlock} as "2001:db8::/32".
   *
   * @param s {@code String} representing either a single IP address or
   *        a CIDR netblock, possibly requiring truncation.
   *
   * @throws IllegalArgumentException if the params are malformed or do not
   *         represent a valid CIDR block.
   * @throws NullPointerException if a parameter is null.
   */
  public static CidrAddressBlock create(String s) {
    return new CidrAddressBlock(parseInetAddress(s), parseNetmask(s), true);
  }

  private static final int AUTO_NETMASK = -1;

  /**
   * The universal constructor.  All public constructors should lead here.
   *
   * @param ip {@link InetAddress}, possibly requiring truncation.
   * @param netmask the number of prefix bits to include in the netmask.
   *     This is between 0 and 32 (for IPv4) or 128 (for IPv6).
   *     The special value {@code AUTO_NETMASK} indicates that the CIDR block
   *     should cover exactly one IP address.
   * @param truncate controls the behavior when an address has extra trailing
   *     bits.  If true, these bits are silently truncated, otherwise this
   *     triggers an exception.
   *
   * @throws IllegalArgumentException if netmask is out of range, or ip has
   *     unexpected trailing bits.
   * @throws NullPointerException if a parameter is null.
   */
  private CidrAddressBlock(InetAddress ip, int netmask, boolean truncate) {
    // A single IP address is always truncated, by definition.
    if (netmask == AUTO_NETMASK) {
      this.ip = ip;
      this.netmask = ip.getAddress().length * 8;
      return;
    }

    // Determine the truncated form of this CIDR block.
    InetAddress truncatedIp = applyNetmask(ip, netmask);

    // If we're not truncating silently, then check for trailing bits.
    if (!truncate && !truncatedIp.equals(ip)) {
      throw new IllegalArgumentException(
          "CIDR block: " + getCidrString(ip, netmask)
          + " is not properly truncated, should have been: "
          + getCidrString(truncatedIp, netmask));
    }

    this.ip = truncatedIp;
    this.netmask = netmask;
  }

  private static String getCidrString(InetAddress ip, int netmask) {
    return ip.getHostAddress() + "/" + netmask;
  }

  /**
   * Attempts to parse an {@link InetAddress} prefix from the given String.
   *
   * @param s a String of the form "217.68.0.0/16" or "2001:db8::/32".
   *
   * @throws IllegalArgumentException if s does not begin with an IP address.
   */
  private static InetAddress parseInetAddress(String s) {
    int slash = s.indexOf('/');
    return InetAddresses.forString((slash < 0) ? s : s.substring(0, slash));
  }

  /**
   * Attempts to parse a netmask from the given String.
   *
   * <p>If the string does not end with a "/xx" suffix, then return AUTO_NETMASK
   * and let the constructor handle it.  Otherwise, we only verify that the
   * suffix is a well-formed nonnegative integer.
   *
   * @param s a String of the form "217.68.0.0/16" or "2001:db8::/32".
   *
   * @throws IllegalArgumentException if s is malformed or does not end with a
   *     valid nonnegative integer.
   */
  private static int parseNetmask(String s) {
    int slash = s.indexOf('/');
    if (slash < 0) {
      return AUTO_NETMASK;
    }
    try {
      return checkNotNegative(Integer.parseInt(s.substring(slash + 1)));
    } catch (NumberFormatException nfe) {
      throw new IllegalArgumentException("Invalid netmask: " + s.substring(slash + 1));
    }
  }

  private static int checkNotNegative(int netmask) {
    checkArgument(netmask >= 0, "CIDR netmask '%s' must not be negative.", netmask);
    return netmask;
  }

  private static InetAddress applyNetmask(InetAddress ip, int netmask) {
    byte[] bytes = ip.getAddress();
    checkArgument(
        (netmask >= 0) && (netmask <= (bytes.length * 8)),
        "CIDR netmask '%s' is out of range: 0 <= netmask <= %s.",
        netmask, (bytes.length * 8));

    // The byte in which the CIDR boundary falls.
    int cidrByte = (netmask == 0) ? 0 : ((netmask - 1) / 8);
    // The number of mask bits within this byte.
    int numBits = netmask - (cidrByte * 8);
    // The bitmask for this byte.
    int bitMask = (-1 << (8 - numBits));

    // Truncate the byte in which the CIDR boundary falls.
    bytes[cidrByte] = (byte) (bytes[cidrByte] & bitMask);

    // All bytes following the cidrByte get zeroed.
    for (int i = cidrByte + 1; i < bytes.length; ++i) {
      bytes[i] = 0;
    }

    try {
      return InetAddress.getByAddress(bytes);
    } catch (UnknownHostException uhe) {
      throw new IllegalArgumentException(
          String.format("Error creating InetAddress from byte array '%s'.",
              Arrays.toString(bytes)),
          uhe);
    }
  }

  /**
   * Returns the standard {@code String} representation of the IP portion of this CIDR block
   * (a.b.c.d, or a:b:c::d)
   *
   * <p>NOTE: This is not reliable for comparison operations. It is more reliable to normalize
   * strings into {@link InetAddress}s and then compare.
   *
   * <p>Consider:
   *
   * <ul>
   *   <li>{@code "10.11.12.0"} is equivalent to {@code "10.11.12.000"}
   *   <li>{@code "2001:db8::"} is equivalent to {@code "2001:0DB8:0000:0000:0000:0000:0000:0000"}
   * </ul>
   */
  public String getIp() {
    return ip.getHostAddress();
  }

  public InetAddress getInetAddress() {
    return ip;
  }

  /**
   * Returns the number of leading bits (prefix size) of the routing prefix.
   */
  public int getNetmask() {
    return netmask;
  }

  /**
   * Returns {@code true} if the supplied {@link InetAddress} is within
   * this {@code CidrAddressBlock}, {@code false} otherwise.
   *
   * <p>This can be used to test if the argument falls within a well-known
   * network range, a la GoogleIp's isGoogleIp(), isChinaIp(), et alia.
   *
   * @param ipAddr {@link InetAddress} to evaluate.
   * @return {@code true} if {@code ipAddr} is logically within this block,
   *         {@code false} otherwise.
   */
  public boolean contains(@Nullable InetAddress ipAddr) {
    if (ipAddr == null) {
      return false;
    }

    // IPv4 CIDR netblocks can never contain IPv6 addresses, and vice versa.
    // Calling getClass() is safe because the Inet4Address and Inet6Address
    // classes are final.
    if (ipAddr.getClass() != ip.getClass()) {
      return false;
    }

    try {
      return ip.equals(applyNetmask(ipAddr, netmask));
    } catch (IllegalArgumentException e) {

      // Something has gone very wrong. This CidrAddressBlock should
      // not have been created with an invalid netmask and a valid
      // netmask should have been successfully applied to "ipAddr" as long
      // as it represents an address of the same family as "this.ip".
      CidrAddressBlockLogger.logger.atWarning().withCause(e).log("Error while applying netmask.");
      return false;
    }
  }

  /**
   * Returns {@code true} if the supplied {@code CidrAddressBlock} is within
   * this {@code CidrAddressBlock}, {@code false} otherwise.
   *
   * <p>This can be used to test if the argument falls within a well-known
   * network range, a la GoogleIp's isGoogleIp(), isChinaIp(), et alia.
   *
   * @param cidr {@code CidrAddressBlock} to evaluate.
   * @return {@code true} if {@code cidr} is logically within this block,
   *         {@code false} otherwise.
   */
  public boolean contains(@Nullable CidrAddressBlock cidr) {
    if (cidr == null) {
      return false;
    }

    if (cidr.netmask < netmask) {
      // No block can contain a network larger than it
      // (in CIDR larger blocks have smaller netmasks).
      return false;
    }

    return contains(cidr.getInetAddress());
  }

  /**
   * Returns {@code true} if the supplied {@code String} is within
   * this {@code CidrAddressBlock}, {@code false} otherwise.
   *
   * <p>This can be used to test if the argument falls within a well-known
   * network range, a la GoogleIp's isGoogleIp(), isChinaIp(), et alia.
   *
   * @param s {@code String} to evaluate.
   * @return {@code true} if {@code s} is logically within this block,
   *         {@code false} otherwise.
   */
  public boolean contains(@Nullable String s) {
    if (s == null) {
      return false;
    }

    try {
      return contains(create(s));
    } catch (IllegalArgumentException iae) {
      return false;
    }
  }

  /**
   * Returns the address that is contained in this {@code CidrAddressBlock}
   * with the most bits set.
   *
   * <p>This can be used to calculate the upper bound address of the address
   * range for this {@code CidrAddressBlock}.
   */
  public InetAddress getAllOnesAddress() {
    byte[] bytes = ip.getAddress();

    // The byte in which the CIDR boundary falls.
    int cidrByte = (netmask == 0) ? 0 : ((netmask - 1) / 8);
    // The number of mask bits within this byte.
    int numBits = netmask - (cidrByte * 8);
    // The bitmask for this byte.
    int bitMask = ~(-1 << (8 - numBits));

    // Set all non-prefix bits where the CIDR boundary falls.
    bytes[cidrByte] = (byte) (bytes[cidrByte] | bitMask);

    // All bytes following the cidrByte get set to all ones.
    for (int i = cidrByte + 1; i < bytes.length; ++i) {
      bytes[i] = (byte) 0xff;
    }

    try {
      return InetAddress.getByAddress(bytes);
    } catch (UnknownHostException uhe) {
      throw new IllegalArgumentException(
          String.format("Error creating InetAddress from byte array '%s'.",
              Arrays.toString(bytes)),
          uhe);
    }
  }

  @Override
  public Iterator<InetAddress> iterator() {
    return new AbstractSequentialIterator<>(ip) {
      @Override
      protected InetAddress computeNext(InetAddress previous) {
        if (InetAddresses.isMaximum(previous)) {
          return null;
        }

        InetAddress next = InetAddresses.increment(previous);
        return contains(next) ? next : null;
      }
    };
  }

  // InetAddresses.coerceToInteger() will be deprecated in the future.
  @SuppressWarnings("deprecation")
  @Override
  public int hashCode() {
    return InetAddresses.coerceToInteger(ip);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (!(o instanceof CidrAddressBlock cidr)) {
      return false;
    }

    return ip.equals(cidr.ip) && (netmask == cidr.netmask);
  }

  @Override
  public String toString() {
    return getCidrString(ip, netmask);
  }

  public static class CidrAddressBlockAdapter extends StringBaseTypeAdapter<CidrAddressBlock> {
    @Override
    protected CidrAddressBlock fromString(String stringValue) {
      return new CidrAddressBlock(stringValue);
    }
  }
}
