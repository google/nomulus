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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.beust.jcommander.IStringConverter;
import com.google.auto.value.AutoValue;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.io.BaseEncoding;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;
import java.util.List;
import org.xbill.DNS.DNSSEC.Algorithm;

@AutoValue
abstract class DsRecord {
  private static final Splitter SPLITTER = Splitter.on(CharMatcher.whitespace()).omitEmptyStrings();

  public abstract int keyTag();

  public abstract int alg();

  public abstract int digestType();

  public abstract String digest();

  private static DsRecord create(int keyTag, int alg, int digestType, String digest) {
    digest = Ascii.toUpperCase(digest);
    checkArgument(
        BaseEncoding.base16().canDecode(digest),
        "digest should be even-lengthed hex, but is %s (length %s)",
        digest,
        digest.length());
    checkArgument(
        DigestType.fromWireValue(digestType).isPresent(),
        "DS record uses an unrecognized digest type: %d",
        digestType);

    // Attempt to convert numeric code for algorithm into its String representation to check its
    // validity
    try {
      Algorithm.string(alg);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format("DS record uses an unrecognized algorithm: %d", alg), e.getCause());
    }

    return new AutoValue_DsRecord(keyTag, alg, digestType, digest);
  }

  /**
   * Parses a string representation of the DS record.
   *
   * <p>The string format accepted is "[keyTag] [alg] [digestType] [digest]" (i.e., the 4 arguments
   * separated by any number of spaces, as it appears in the Zone file)
   */
  public static DsRecord parse(String dsRecord) {
    List<String> elements = SPLITTER.splitToList(dsRecord);
    checkArgument(
        elements.size() == 4,
        "dsRecord %s should have 4 parts, but has %s",
        dsRecord,
        elements.size());
    return DsRecord.create(
        Integer.parseUnsignedInt(elements.get(0)),
        Integer.parseUnsignedInt(elements.get(1)),
        Integer.parseUnsignedInt(elements.get(2)),
        elements.get(3));
  }

  public SoyMapData toSoyData() {
    return new SoyMapData(
        "keyTag", keyTag(),
        "alg", alg(),
        "digestType", digestType(),
        "digest", digest());
  }

  public static SoyListData convertToSoy(List<DsRecord> dsRecords) {
    return new SoyListData(dsRecords.stream().map(DsRecord::toSoyData).collect(toImmutableList()));
  }

  public static class Converter implements IStringConverter<DsRecord> {
    @Override
    public DsRecord convert(String dsRecord) {
      return DsRecord.parse(dsRecord);
    }
  }
}
