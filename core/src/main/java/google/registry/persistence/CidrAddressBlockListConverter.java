// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import google.registry.util.CidrAddressBlock;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** JPA converter to for storing/retrieving {@link List<CidrAddressBlock>} objects. */
@Converter(autoApply = true)
public class CidrAddressBlockListConverter
    implements AttributeConverter<List<CidrAddressBlock>, String> {

  @Override
  @Nullable
  public String convertToDatabaseColumn(@Nullable List<CidrAddressBlock> attribute) {
    return attribute == null ? null : Joiner.on(",").join(attribute);
  }

  @Override
  @Nullable
  public List<CidrAddressBlock> convertToEntityAttribute(@Nullable String dbData) {
    if (dbData == null) {
      return null;
    }
    return Splitter.on(",").splitToList(dbData).stream()
        .map(CidrAddressBlock::new)
        .collect(Collectors.toList());
  }
}
