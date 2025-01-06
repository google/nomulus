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

package google.registry.model.host;

import com.google.common.net.InetAddresses;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlValue;
import jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter;
import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Used by JAXB to convert InetAddress objects to and from AddrType objects, which are the
 * intermediate representations of IP addresses in EPP host commands.
 */
public final class InetAddressAdapter
    extends XmlAdapter<InetAddressAdapter.AddressShim, InetAddress> {

  /**
   * Intermediate representation of an IP address on a host object. This object only exists
   * temporarily until it is converted by JAXB to and from an InetAddress via this adapter.
   */
  static class AddressShim {
    @XmlValue
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    String ipAddress;

    @XmlAttribute(name = "ip")
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    String ipVersion;
  }

  @Override
  public AddressShim marshal(InetAddress inetAddress) {
    AddressShim shim = new AddressShim();
    shim.ipAddress = InetAddresses.toAddrString(inetAddress);
    shim.ipVersion = (inetAddress instanceof Inet6Address) ? "v6" : "v4";
    return shim;
  }

  @Override
  public InetAddress unmarshal(AddressShim shim) throws IpVersionMismatchException {
    InetAddress inetAddress = InetAddresses.forString(shim.ipAddress);
    // Enforce that "v6" is used iff the address is ipv6. (For ipv4, "v4" is allowed to be missing.)
    if (inetAddress instanceof Inet6Address != "v6".equals(shim.ipVersion)) {
      throw new IpVersionMismatchException();
    }
    return inetAddress;
  }

  /** Exception for when the specified type of an address (v4/v6) doesn't match its actual type. */
  public static class IpVersionMismatchException extends Exception {}
}
