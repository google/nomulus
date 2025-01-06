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

package google.registry.rde;


import google.registry.model.common.Cursor.CursorType;
import google.registry.model.rde.RdeMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.BooleanCoder;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Container representing a single RDE or BRDA XML escrow deposit that needs to be created.
 *
 * <p>There are some {@code @Nullable} fields here because Optionals aren't Serializable.
 *
 * <p>Note that this class is serialized in two ways: by Beam pipelines using custom serialization
 * mechanism and the {@code Coder} API, and by Java serialization when passed as command-line
 * arguments (see {@code RdePipeline#decodePendingDeposits}). The latter requires safe
 * deserialization because the data crosses credential boundaries (See {@code
 * SafeObjectInputStream}).
 *
 * @param manual True if deposits should be generated via manual operation, which does not update
 *     the cursor, and saves the generated deposits in a special manual subdirectory tree.
 * @param tld TLD for which a deposit should be generated.
 * @param watermarkStr String representation of the watermark date for which a deposit should be
 *     generated.
 * @param mode Which type of deposit to generate: full (RDE) or thin (BRDA).
 * @param cursor The cursor type to update (not used in manual operation).
 * @param intervalStr String representation of the amount of time to increment the cursor (not used
 *     in manual operation).
 * @param directoryWithTrailingSlash Subdirectory of bucket/manual in which files should be placed,
 *     including a trailing slash (used only in manual operation).
 * @param revision Revision number for generated files; if absent, use the next available in the
 *     sequence (used only in manual operation).
 */
public record PendingDeposit(
    boolean manual,
    String tld,
    String watermarkStr,
    RdeMode mode,
    @Nullable CursorType cursor,
    @Nullable String intervalStr,
    @Nullable String directoryWithTrailingSlash,
    @Nullable Integer revision)
    implements Serializable {

  public DateTime watermark() {
    return DateTime.parse(watermarkStr);
  }

  public Duration interval() {
    return intervalStr == null ? null : Duration.parse(intervalStr);
  }

  @Serial private static final long serialVersionUID = 3141095605225904433L;

  public static PendingDeposit create(
      String tld, DateTime watermark, RdeMode mode, CursorType cursor, Duration interval) {
    return new PendingDeposit(
        false, tld, watermark.toString(), mode, cursor, interval.toString(), null, null);
  }

  public static PendingDeposit createInManualOperation(
      String tld,
      DateTime watermark,
      RdeMode mode,
      String directoryWithTrailingSlash,
      @Nullable Integer revision) {
    return new PendingDeposit(
        true, tld, watermark.toString(), mode, null, null, directoryWithTrailingSlash, revision);
  }

  /**
   * A deterministic coder for {@link PendingDeposit} used during a GroupBy transform.
   *
   * <p>We cannot use a {@code SerializableCoder} directly for two reasons: the default
   * serialization does not guarantee determinism, which is required by GroupBy in Beam; and the
   * default deserialization is not robust against deserialization-based attacks (See {@code
   * SafeObjectInputStream} for more information).
   */
  public static class PendingDepositCoder extends AtomicCoder<PendingDeposit> {

    private PendingDepositCoder() {
      super();
    }

    private static final PendingDepositCoder INSTANCE = new PendingDepositCoder();

    public static PendingDepositCoder of() {
      return INSTANCE;
    }

    @Override
    public void encode(PendingDeposit value, OutputStream outStream) throws IOException {
      BooleanCoder.of().encode(value.manual(), outStream);
      StringUtf8Coder.of().encode(value.tld(), outStream);
      StringUtf8Coder.of().encode(value.watermarkStr(), outStream);
      StringUtf8Coder.of().encode(value.mode().name(), outStream);
      NullableCoder.of(StringUtf8Coder.of())
          .encode(
              Optional.ofNullable(value.cursor()).map(CursorType::name).orElse(null), outStream);
      NullableCoder.of(StringUtf8Coder.of()).encode(value.intervalStr(), outStream);
      NullableCoder.of(StringUtf8Coder.of()).encode(value.directoryWithTrailingSlash(), outStream);
      NullableCoder.of(VarIntCoder.of()).encode(value.revision(), outStream);
    }

    @Override
    public PendingDeposit decode(InputStream inStream) throws IOException {
      return new PendingDeposit(
          BooleanCoder.of().decode(inStream),
          StringUtf8Coder.of().decode(inStream),
          StringUtf8Coder.of().decode(inStream),
          RdeMode.valueOf(StringUtf8Coder.of().decode(inStream)),
          Optional.ofNullable(NullableCoder.of(StringUtf8Coder.of()).decode(inStream))
              .map(CursorType::valueOf)
              .orElse(null),
          NullableCoder.of(StringUtf8Coder.of()).decode(inStream),
          NullableCoder.of(StringUtf8Coder.of()).decode(inStream),
          NullableCoder.of(VarIntCoder.of()).decode(inStream));
    }
  }
}
