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

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.google.common.testing.NullPointerTester;
import com.google.common.testing.SerializableTester;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import junit.framework.TestCase;

/**
 * Tests for {@link CidrAddressBlock}.
 *
 */
public class CidrAddressBlockTest extends TestCase {

  public void testNulls() {
    NullPointerTester tester = new NullPointerTester();
    tester.testAllPublicStaticMethods(CidrAddressBlock.class);
    tester.testAllPublicConstructors(CidrAddressBlock.class);
    tester.testAllPublicInstanceMethods(new CidrAddressBlock("::/0"));
  }

  public void testConstructorWithNetmask() {
    CidrAddressBlock b0 = new CidrAddressBlock("22.24.66.0/24");
    assertEquals("22.24.66.0", b0.getIp());
    assertEquals(24, b0.getNetmask());
  }

  public void testConstructorPicksNetmask() {
    CidrAddressBlock b0 = new CidrAddressBlock("64.132.1.2");
    assertEquals(32, b0.getNetmask());
  }

  public void testConstructorDoesntThrow() {
    new CidrAddressBlock("64.132.0.0/16");
    new CidrAddressBlock("128.142.217.0/24");
    new CidrAddressBlock("35.213.0.0", 16);
    new CidrAddressBlock("89.23.164.0", 24);
  }

  public void testInetAddressConstructor() {
    CidrAddressBlock b0 = new CidrAddressBlock(InetAddresses.forString("1.2.3.4"));
    assertEquals(32, b0.getNetmask());
    assertEquals("1.2.3.4", b0.getIp());

    CidrAddressBlock b1 = new CidrAddressBlock("2001:db8::/32");
    assertEquals(InetAddresses.forString("2001:db8::"), b1.getInetAddress());
    assertEquals(32, b1.getNetmask());
    b1 = new CidrAddressBlock("2001:db8::1");
    assertEquals(128, b1.getNetmask());
    b1 = new CidrAddressBlock(InetAddresses.forString("5ffe::1"));
    assertEquals(128, b1.getNetmask());
    assertEquals("5ffe:0:0:0:0:0:0:1", b1.getIp());
  }

  public void testCornerCasesSucceed() {
    new CidrAddressBlock("0.0.0.0/32");
    new CidrAddressBlock("255.255.255.255/32");
    new CidrAddressBlock("255.255.255.254/31");
    new CidrAddressBlock("128.0.0.0/1");
    new CidrAddressBlock("0.0.0.0/0");
    new CidrAddressBlock("::");
    new CidrAddressBlock("::/128");
    new CidrAddressBlock("::/0");
    new CidrAddressBlock("8000::/1");
    new CidrAddressBlock("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff");
    new CidrAddressBlock("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128");
    new CidrAddressBlock("ffff:ffff:ffff:ffff:ffff:ffff:ffff:fffe/127");
  }

  public void testFailure() {
    assertConstructionFails("");
    assertConstructionFails("0");
    assertConstructionFails("1");
    assertConstructionFails("alskjewosvdhfshjwklerjlkj");
    assertConstructionFails("lawkejrlaksdj/24");
    assertConstructionFails("192.168.34.23/awlejkrhlsdhf");
    assertConstructionFails("192.168.34.23/");
    assertConstructionFails("192.168.34.23/-1");
    assertConstructionFails("192.239.0.0/12");
    assertConstructionFails("192.168.223.15/33");
    assertConstructionFails("268.23.53.0/24");
    assertConstructionFails("192..23.53.0/24");
    assertConstructionFails("192..53.0/24");

    assertConstructionFails("192.23.230.0/16");
    assertConstructionFails("192.23.255.0/16");
    assertConstructionFails("192.23.18.1/16");
    assertConstructionFails("123.34.111.240/35");

    assertConstructionFails("160.32.34.23", 240);
    assertConstructionFails("alskjewosvdhfshjwklerjlkj", 24);
    assertConstructionFails("160.32.34.23", 1);

    assertConstructionFails("2001:db8::1/");
    assertConstructionFails("2001:db8::1", -1);
    assertConstructionFails("2001:db8::1", 0);
    assertConstructionFails("2001:db8::1", 32);
    assertConstructionFails("2001:db8::1", 129);
    assertConstructionFails("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/127");
  }

  public void testTruncation() {
    ImmutableMap<String, String> netblocks = new ImmutableMap.Builder<String, String>()
        // IPv4
        .put("1.2.3.4/0", "0.0.0.0/0")
        .put("1.2.3.4/24", "1.2.3.0/24")
        .put("1.2.3.255/27", "1.2.3.224/27")
        .put("1.2.3.255/28", "1.2.3.240/28")
        // IPv6
        .put("2001:db8::1/0", "::/0")
        .put("2001:db8::1/16", "2001::/16")
        .put("2001:db8::1/21", "2001:800::/21")
        .put("2001:db8::1/22", "2001:c00::/22")
        .build();
    for (Map.Entry<String, String> pair : netblocks.entrySet()) {
      assertConstructionFails(pair.getKey());
      assertEquals(
          new CidrAddressBlock(pair.getValue()),
          CidrAddressBlock.create(pair.getKey()));
      assertEquals(
          CidrAddressBlock.create(pair.getKey()),
          CidrAddressBlock.create(pair.getValue()));
    }
  }

  public void testContains() {
    CidrAddressBlock b0 = CidrAddressBlock.create("172.24.255.0/24");
    assertTrue(b0.contains(b0));
    assertTrue(b0.contains(b0.getIp()));
    assertTrue(b0.contains(b0.getInetAddress()));

    /*
     * Test an "IPv4 compatible" IPv6 address.
     *
     * "IPv4 compatible" addresses are not IPv4 addresses.  They are
     * written this way for "convenience" and appear on the wire as
     * 128bit address with 96 leading bits of 0.
     */
    assertFalse(b0.contains("::172.24.255.0"));

    /*
     * Test an "IPv4 mapped" IPv6 address.
     *
     * "IPv4 mapped" addresses in Java create only Inet4Address objects.
     * For more detailed history see the discussion of "mapped" addresses
     * in com.google.common.net.InetAddresses.
     */
    assertTrue(b0.contains("::ffff:172.24.255.0"));

    assertFalse(b0.contains((InetAddress) null));
    assertFalse(b0.contains((CidrAddressBlock) null));
    assertFalse(b0.contains((String) null));
    assertFalse(b0.contains("bogus IP address or CIDR block"));

    CidrAddressBlock b1 = CidrAddressBlock.create("172.24.255.0/23");
    assertFalse(b0.contains(b1));
    assertTrue(b1.contains(b0));

    CidrAddressBlock b2 = CidrAddressBlock.create("2001:db8::/48");
    assertFalse(b0.contains(b2));
    assertFalse(b0.contains(b2.getIp()));
    assertFalse(b0.contains(b2.getInetAddress()));
    assertFalse(b1.contains(b2));
    assertFalse(b1.contains(b2.getIp()));
    assertFalse(b1.contains(b2.getInetAddress()));
    assertFalse(b2.contains(b0));
    assertFalse(b2.contains(b0.getIp()));
    assertFalse(b2.contains(b0.getInetAddress()));
    assertFalse(b2.contains(b1));
    assertFalse(b2.contains(b1.getIp()));
    assertFalse(b2.contains(b1.getInetAddress()));
    assertTrue(b2.contains(b2));
    assertTrue(b2.contains(b2.getIp()));
    assertTrue(b2.contains(b2.getInetAddress()));

    CidrAddressBlock b3 = CidrAddressBlock.create("2001:db8::/32");
    assertFalse(b2.contains(b3));
    assertTrue(b3.contains(b2));

    CidrAddressBlock allIPv4 = CidrAddressBlock.create("0.0.0.0/0");
    assertTrue(allIPv4.contains(b0));
    assertTrue(allIPv4.contains(b1));
    assertFalse(b0.contains(allIPv4));
    assertFalse(b1.contains(allIPv4));
    assertFalse(allIPv4.contains(b2));
    assertFalse(allIPv4.contains(b3));
    assertFalse(b2.contains(allIPv4));
    assertFalse(b3.contains(allIPv4));
    assertFalse(allIPv4.contains("::172.24.255.0"));
    assertTrue(allIPv4.contains("::ffff:172.24.255.0"));

    CidrAddressBlock allIPv6 = CidrAddressBlock.create("::/0");
    assertTrue(allIPv6.contains(b2));
    assertTrue(allIPv6.contains(b3));
    assertFalse(b2.contains(allIPv6));
    assertFalse(b3.contains(allIPv6));
    assertFalse(allIPv6.contains(b0));
    assertFalse(allIPv6.contains(b1));
    assertFalse(b0.contains(allIPv6));
    assertFalse(b1.contains(allIPv6));
    assertTrue(allIPv6.contains("::172.24.255.0"));
    assertFalse(allIPv6.contains("::ffff:172.24.255.0"));

    assertFalse(allIPv4.contains(allIPv6));
    assertFalse(allIPv6.contains(allIPv4));
  }

  public void testGetAllOnesAddress() {
    // <CIDR block> -> <expected getAllOnesAddress()>
    ImmutableMap<String, String> testCases = new ImmutableMap.Builder<String, String>()
        .put("172.24.255.0/24", "172.24.255.255")
        .put("172.24.0.0/15", "172.25.255.255")
        .put("172.24.254.0/23", "172.24.255.255")
        .put("172.24.255.0/32", "172.24.255.0")
        .put("0.0.0.0/0", "255.255.255.255")
        .put("2001:db8::/48", "2001:db8::ffff:ffff:ffff:ffff:ffff")
        .put("2001:db8::/32", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff")
        .put("2001:db8::/128", "2001:db8::")
        .put("::/0", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        .build();

    for (Map.Entry<String, String> testCase : testCases.entrySet()) {
      assertEquals(
          InetAddresses.forString(testCase.getValue()),
          CidrAddressBlock.create(testCase.getKey()).getAllOnesAddress());
    }
  }

  public void testEqualsAndHashCode() {
    CidrAddressBlock b0 = new CidrAddressBlock("172.24.66.0/24");
    CidrAddressBlock b1 = new CidrAddressBlock("172.24.66.0", 24);
    CidrAddressBlock b2 = new CidrAddressBlock("172.24.0.0/16");
    CidrAddressBlock b3 = new CidrAddressBlock("172.24.65.0/24");
    assertEquals(b0, b1);
    assertEquals(b0, new CidrAddressBlock(b0.toString()));
    assertEquals(b0.hashCode(), b1.hashCode());
    assertTrue(!b0.equals(b2));
    assertTrue(!b0.equals(b3));

    b0 = new CidrAddressBlock("2001:db8::/64");
    b1 = new CidrAddressBlock("2001:0DB8:0:0::", 64);
    b2 = new CidrAddressBlock("2001:db8::/32");
    b3 = new CidrAddressBlock("2001:0DB8:0:1::", 64);
    assertEquals(b0, b1);
    assertEquals(b0, new CidrAddressBlock(b0.toString()));
    assertEquals(b0.hashCode(), b1.hashCode());
    assertNotEquals(b0, b2);
    assertNotEquals(b0, b3);
  }

  public void testIterate() {
    CidrAddressBlock b0 = new CidrAddressBlock("172.24.66.0/24");
    int count = 0;
    for (InetAddress addr : b0) {
      assertTrue(b0.contains(addr));
      ++count;
    }
    assertEquals(256, count);

    CidrAddressBlock b1 = new CidrAddressBlock("2001:0DB8:0:0::/120");
    count = 0;
    for (InetAddress addr : b1) {
      assertTrue(b1.contains(addr));
      ++count;
    }
    assertEquals(256, count);

    CidrAddressBlock b2 = new CidrAddressBlock("255.255.255.254/31");
    Iterator<InetAddress> i = b2.iterator();
    i.next();
    i.next();
    assertThrows(NoSuchElementException.class, i::next);
  }

  public void testSerializability() {
    SerializableTester.reserializeAndAssert(new CidrAddressBlock("22.24.66.0/24"));
    SerializableTester.reserializeAndAssert(new CidrAddressBlock("64.132.1.2"));
    SerializableTester.reserializeAndAssert(
        new CidrAddressBlock(InetAddresses.forString("1.2.3.4")));

    SerializableTester.reserializeAndAssert(new CidrAddressBlock("2001:db8::/32"));
    SerializableTester.reserializeAndAssert(new CidrAddressBlock("2001:db8::1"));
    SerializableTester.reserializeAndAssert(
        new CidrAddressBlock(InetAddresses.forString("5ffe::1")));
  }

  private static void assertConstructionFails(String ip) {
    assertThrows(IllegalArgumentException.class, () -> new CidrAddressBlock(ip));
  }

  private static void assertConstructionFails(String ip, int netmask) {
    assertThrows(IllegalArgumentException.class, () -> new CidrAddressBlock(ip, netmask));
  }
}
