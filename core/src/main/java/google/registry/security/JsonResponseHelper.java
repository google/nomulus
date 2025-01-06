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

package google.registry.security;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * Helper class for JSON API servlets to send response messages.
 *
 * @see JsonHttp
 */
public final class JsonResponseHelper {

  /** Possible results of an RPC operation. */
  public enum Status { SUCCESS, ERROR }

  /** Creates a JSON response message securely to the browser client with a parser breaker. */
  public static ImmutableMap<String, Object> create(
      Status status, String message, Iterable<? extends Map<String, ?>> results) {
    return ImmutableMap.of(
        "status", status.toString(),
        "message", checkNotNull(message, "message"),
        "results", ImmutableList.copyOf(results));
  }

  /** Same as {@link #create(Status, String, Iterable)} but with zero results. */
  public static ImmutableMap<String, Object> create(Status status, String message) {
    return create(status, message, ImmutableList.of());
  }

  /** Same as {@link #create(Status, String, Iterable)} but with only one results. */
  public static ImmutableMap<String, Object> create(
      Status status, String message, Map<String, ?> result) {
    return create(status, message, ImmutableList.<Map<String, ?>>of(result));
  }

  /** Creates a JSON response message when a submitted form field is invalid. */
  public static ImmutableMap<String, Object> createFormFieldError(
      String message, String formFieldName) {
    return ImmutableMap.of(
        "status", Status.ERROR.toString(),
        "message", checkNotNull(message, "message"),
        "field", checkNotNull(formFieldName, "formFieldName"),
        "results", ImmutableList.of());
  }
}
