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

package google.registry.whois;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Exception that gets thrown when WHOIS command isn't successful. */
public final class WhoisException extends Exception implements WhoisResponse {

  private final DateTime timestamp;
  private final int status;

  /** @see #WhoisException(DateTime, int, String, Throwable) */
  WhoisException(DateTime timestamp, int status, String message) {
    this(timestamp, status, message, null);
  }

  /**
   * Construct an exception explaining why a WHOIS request has failed.
   *
   * @param timestamp should be set to the time at which this request was processed.
   * @param status A non-2xx HTTP status code to indicate type of failure.
   * @param message is displayed to the user so you should be careful about tainted data.
   * @param cause the original exception or {@code null}.
   * @throws IllegalArgumentException if {@code !(300 <= status < 700)}
   */
  WhoisException(DateTime timestamp, int status, String message, @Nullable Throwable cause) {
    super(message, cause);
    checkArgument(300 <= status && status < 700,
        "WhoisException status must be a non-2xx HTTP status code: %s", status);
    this.timestamp = checkNotNull(timestamp, "timestamp");
    this.status = status;
  }

  /** Returns the time at which this WHOIS request was processed. */
  @Override
  public DateTime getTimestamp() {
    return timestamp;
  }

  /** Returns a non-2xx HTTP status code to differentiate types of failure. */
  public int getStatus() {
    return status;
  }

  @Override
  public WhoisResponseResults getResponse(boolean preferUnicode, String disclaimer) {
    String plaintext = new WhoisResponseImpl.BasicEmitter()
        .emitRawLine(getMessage())
        .emitLastUpdated(getTimestamp())
        .emitFooter(disclaimer)
        .toString();
    return WhoisResponseResults.create(plaintext, 0);
  }

  /** Exception that wraps WhoisExceptions returned from Retrier. */
  public static final class UncheckedWhoisException extends RuntimeException {
    UncheckedWhoisException(WhoisException whoisException) {
      super(whoisException);
    }
  }
}
