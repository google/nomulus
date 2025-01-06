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

import static com.google.common.collect.ImmutableList.toImmutableList;

import google.registry.util.CidrAddressBlock;
import java.util.List;
import java.util.stream.Stream;

/** Hibernate custom type for {@link List} of {@link CidrAddressBlock}. */
public class CidrBlockListUserType
    extends StringCollectionUserType<CidrAddressBlock, List<CidrAddressBlock>> {

  @Override
  String[] toJdbcObject(List<CidrAddressBlock> collection) {
    return collection.stream().map(Object::toString).toArray(String[]::new);
  }

  @Override
  List<CidrAddressBlock> toEntity(String[] data) {
    return Stream.of(data).map(CidrAddressBlock::create).collect(toImmutableList());
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<List<CidrAddressBlock>> returnedClass() {
    return (Class<List<CidrAddressBlock>>) ((Object) List.class);
  }
}
