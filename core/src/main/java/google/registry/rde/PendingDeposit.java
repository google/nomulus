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

package google.registry.rde;

import com.google.auto.value.AutoValue;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.rde.RdeMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.AtomicCoder;
import org.apache.beam.sdk.coders.BooleanCoder;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Container representing a single RDE or BRDA XML escrow deposit that needs to be created.
 *
 * <p>There are some {@code @Nullable} fields here because Optionals aren't Serializable.
 */
@AutoValue
public abstract class PendingDeposit implements Serializable {

  private static final long serialVersionUID = 3141095605225904433L;

  /**
   * True if deposits should be generated via manual operation, which does not update the cursor,
   * and saves the generated deposits in a special manual subdirectory tree.
   */
  public abstract boolean manual();

  /** TLD for which a deposit should be generated. */
  public abstract String tld();

  /** Watermark date for which a deposit should be generated. */
  public abstract DateTime watermark();

  /** Which type of deposit to generate: full (RDE) or thin (BRDA). */
  public abstract RdeMode mode();

  /** The cursor type to update (not used in manual operation). */
  @Nullable
  public abstract CursorType cursor();

  /** Amount of time to increment the cursor (not used in manual operation). */
  @Nullable
  public abstract Duration interval();

  /**
   * Subdirectory of bucket/manual in which files should be placed, including a trailing slash (used
   * only in manual operation).
   */
  @Nullable
  public abstract String directoryWithTrailingSlash();

  /**
   * Revision number for generated files; if absent, use the next available in the sequence (used
   * only in manual operation).
   */
  @Nullable
  public abstract Integer revision();

  public static PendingDeposit create(
      String tld, DateTime watermark, RdeMode mode, CursorType cursor, Duration interval) {
    return new AutoValue_PendingDeposit(false, tld, watermark, mode, cursor, interval, null, null);
  }

  static PendingDeposit createInManualOperation(
      String tld,
      DateTime watermark,
      RdeMode mode,
      String directoryWithTrailingSlash,
      @Nullable Integer revision) {
    return new AutoValue_PendingDeposit(
        true, tld, watermark, mode, null, null, directoryWithTrailingSlash, revision);
  }

  PendingDeposit() {}

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
      SerializableCoder.of(DateTime.class).encode(value.watermark(), outStream);
      SerializableCoder.of(RdeMode.class).encode(value.mode(), outStream);
      NullableCoder.of(SerializableCoder.of(CursorType.class)).encode(value.cursor(), outStream);
      NullableCoder.of(SerializableCoder.of(Duration.class)).encode(value.interval(), outStream);
      NullableCoder.of(StringUtf8Coder.of()).encode(value.directoryWithTrailingSlash(), outStream);
      NullableCoder.of(VarIntCoder.of()).encode(value.revision(), outStream);
    }

    @Override
    public PendingDeposit decode(InputStream inStream) throws IOException {
      return new AutoValue_PendingDeposit(
          BooleanCoder.of().decode(inStream),
          StringUtf8Coder.of().decode(inStream),
          SerializableCoder.of(DateTime.class).decode(inStream),
          SerializableCoder.of(RdeMode.class).decode(inStream),
          NullableCoder.of(SerializableCoder.of(CursorType.class)).decode(inStream),
          NullableCoder.of(SerializableCoder.of(Duration.class)).decode(inStream),
          NullableCoder.of(StringUtf8Coder.of()).decode(inStream),
          NullableCoder.of(VarIntCoder.of()).decode(inStream));
    }
  }
}
