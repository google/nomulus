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

package google.registry.keyring.api;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.flogger.FluentLogger;
import google.registry.util.ComparingInvocationHandler;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nullable;
import org.bouncycastle.bcpg.BCPGKey;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;

/**
 * Checks that a second keyring returns the same result as the current one.
 *
 * <p>Will behave exactly like the "actualKeyring" - as in will throw / return the exact same values
 * - no matter what the "secondKeyring" does. But will log a warning if "secondKeyring" acts
 * differently than "actualKeyring".
 *
 * <p>If both keyrings threw exceptions, there is no check whether the exeptions are the same. The
 * assumption is that an error happened in both, but they might report that error differently.
 */
public final class ComparatorKeyring extends ComparingInvocationHandler<Keyring> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ComparatorKeyring(Keyring original, Keyring second) {
    super(Keyring.class, original, second);
  }

  /**
   * Returns an instance of Keyring that is an exact proxy of "original".
   *
   * <p>This proxy will log any differences in return value or thrown exceptions with "second".
   */
  public static Keyring create(Keyring original, Keyring second) {
    return new ComparatorKeyring(original, second).makeProxy();
  }

  @Override
  protected void log(Method method, String message) {
    logger.atSevere().log("ComparatorKeyring.%s: %s", method.getName(), message);
  }

  /** Implements equals for the PGP classes. */
  @Override
  protected boolean compareResults(Method method, @Nullable Object a, @Nullable Object b) {
    Class<?> clazz = method.getReturnType();
    if (PGPPublicKey.class.equals(clazz)) {
      return compare((PGPPublicKey) a, (PGPPublicKey) b);
    }
    if (PGPPrivateKey.class.equals(clazz)) {
      return compare((PGPPrivateKey) a, (PGPPrivateKey) b);
    }
    if (PGPKeyPair.class.equals(clazz)) {
      return compare((PGPKeyPair) a, (PGPKeyPair) b);
    }
    return super.compareResults(method, a, b);
  }

  /** Implements toString for the PGP classes. */
  @Override
  protected String stringifyResult(Method method, @Nullable Object a) {
    Class<?> clazz = method.getReturnType();
    if (PGPPublicKey.class.equals(clazz)) {
      return stringify((PGPPublicKey) a);
    }
    if (PGPPrivateKey.class.equals(clazz)) {
      return stringify((PGPPrivateKey) a);
    }
    if (PGPKeyPair.class.equals(clazz)) {
      return stringify((PGPKeyPair) a);
    }
    return super.stringifyResult(method, a);
  }

  @Override
  protected String stringifyThrown(Method method, Throwable throwable) {
    StringWriter stringWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(stringWriter);
    throwable.printStackTrace(printWriter);
    return String.format("%s\nStack trace:\n%s", throwable.toString(), stringWriter.toString());
  }

  // .equals implementation for PGP types.

  @VisibleForTesting
  static boolean compare(@Nullable PGPKeyPair a, @Nullable PGPKeyPair b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return compare(a.getPublicKey(), b.getPublicKey())
        && compare(a.getPrivateKey(), b.getPrivateKey());
  }

  @VisibleForTesting
  static boolean compare(@Nullable PGPPublicKey a, @Nullable PGPPublicKey b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    try {
      return Arrays.equals(a.getFingerprint(), b.getFingerprint())
          && Arrays.equals(a.getEncoded(), b.getEncoded());
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "ComparatorKeyring error: PGPPublicKey.getEncoded failed.");
      return false;
    }
  }

  @VisibleForTesting
  static boolean compare(@Nullable PGPPrivateKey a, @Nullable PGPPrivateKey b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return a.getKeyID() == b.getKeyID()
        && compare(a.getPrivateKeyDataPacket(), b.getPrivateKeyDataPacket())
        && compare(a.getPublicKeyPacket(), b.getPublicKeyPacket());
  }

  @VisibleForTesting
  static boolean compare(PublicKeyPacket a, PublicKeyPacket b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    try {
      return Arrays.equals(a.getEncoded(), b.getEncoded());
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "ComparatorKeyring error: PublicKeyPacket.getEncoded failed.");
      return false;
    }
  }

  @VisibleForTesting
  static boolean compare(BCPGKey a, BCPGKey b) {
    if (a == null || b == null) {
      return a == null && b == null;
    }
    return Objects.equals(a.getFormat(), b.getFormat())
        && Arrays.equals(a.getEncoded(), b.getEncoded());
  }

  // toString implementations

  @VisibleForTesting
  static String stringify(PGPKeyPair a) {
    if (a == null) {
      return "null";
    }
    return MoreObjects.toStringHelper(PGPKeyPair.class)
        .addValue(stringify(a.getPublicKey()))
        .addValue(stringify(a.getPrivateKey()))
        .toString();
  }

  @VisibleForTesting
  static String stringify(PGPPublicKey a) {
    if (a == null) {
      return "null";
    }

    StringBuilder builder = new StringBuilder();
    for (byte b : a.getFingerprint()) {
      builder.append(String.format("%02x:", b));
    }
    return MoreObjects.toStringHelper(PGPPublicKey.class)
        .add("fingerprint", builder.toString())
        .toString();
  }

  @VisibleForTesting
  static String stringify(PGPPrivateKey a) {
    if (a == null) {
      return "null";
    }

    // We need to be careful what information we output here. The private key should be private, and
    // I'm not sure what is safe to put in the logs.
    return MoreObjects.toStringHelper(PGPPrivateKey.class)
        .add("keyId", a.getKeyID())
        .toString();
  }
}
