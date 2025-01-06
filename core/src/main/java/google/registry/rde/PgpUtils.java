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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Streams;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;

/** Utilities that help us deal with Pgp objects. */
final class PgpUtils {

  private PgpUtils() {}

  /** Safely extracts an object from an OpenPGP message. */
  static <T> T pgpCast(@Nullable Object object, Class<T> expect) {
    checkNotNull(object, "PGP error: expected %s but out of objects", expect.getSimpleName());
    checkState(
        expect.isAssignableFrom(object.getClass()),
        "PGP error: expected %s but got %s",
        expect.getSimpleName(),
        object.getClass().getSimpleName());
    return expect.cast(object);
  }

  static <T> T readSinglePgpObject(InputStream input, Class<T> expect) {
    try {
      PGPObjectFactory fact = new JcaPGPObjectFactory(input);
      return PgpUtils.pgpCast(fact.nextObject(), expect);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Stream the "list" that is PGPEncryptedDataList.
   *
   * <p>PGPEncryptedDataList is apparently very "legacy". It's not actually a list, and although it
   * implements "Iterable", there's no explicit type given. This requires multiple casts, which is
   * ugly. Moving the casts to a dedicated function makes it all clearer.
   */
  static <T> Stream<T> stream(PGPEncryptedDataList ciphertexts, Class<T> expect) {
    return Streams.stream((Iterable<?>) ciphertexts)
        .map(ciphertext -> PgpUtils.pgpCast(ciphertext, expect));
  }
}
