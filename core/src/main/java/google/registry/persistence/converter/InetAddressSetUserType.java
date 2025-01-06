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

package google.registry.persistence.converter;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Hibernate custom type for {@link Set} of {@link InetAddress} objects. */
public class InetAddressSetUserType
    extends StringCollectionUserType<InetAddress, Set<InetAddress>> {

  @Override
  String[] toJdbcObject(Set<InetAddress> collection) {
    return collection.stream()
        .map(addr -> InetAddresses.toAddrString(addr))
        .toList()
        .toArray(new String[0]);
  }

  @Nullable
  @Override
  Set<InetAddress> toEntity(@Nullable String[] data) {
    return data == null
        ? null
        : Stream.of(data).map(InetAddresses::forString).collect(toImmutableSet());
  }

  @Override
  public Class<Set<InetAddress>> returnedClass() {
    return (Class<Set<InetAddress>>) ((Object) Set.class);
  }
}
