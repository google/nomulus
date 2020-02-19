// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Charsets.US_ASCII;
import static com.google.common.hash.Funnels.stringFunnel;

import com.google.common.hash.BloomFilter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** JPA converter for ASCII String {@link BloomFilter}s. */
@Converter(autoApply = true)
public class BloomFilterConverter implements AttributeConverter<BloomFilter<String>, byte[]> {

  @Override
  @Nullable
  public byte[] convertToDatabaseColumn(@Nullable BloomFilter<String> entity) {
    if (entity == null) {
      return null;
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      entity.writeTo(bos);
    } catch (IOException e) {
      throw new UncheckedIOException("Error saving Bloom filter data", e);
    }
    return bos.toByteArray();
  }

  @Override
  @Nullable
  public BloomFilter<String> convertToEntityAttribute(@Nullable byte[] columnValue) {
    if (columnValue == null) {
      return null;
    }
    try {
      return BloomFilter.readFrom(new ByteArrayInputStream(columnValue), stringFunnel(US_ASCII));
    } catch (IOException e) {
      throw new UncheckedIOException("Error loading Bloom filter data", e);
    }
  }
}
