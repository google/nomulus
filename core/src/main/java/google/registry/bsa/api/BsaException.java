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

package google.registry.bsa.api;

public class BsaException extends RuntimeException {

  private final boolean retriable;

  public BsaException(Throwable cause, boolean retriable) {
    super(cause);
    this.retriable = retriable;
  }

  public BsaException(String message, boolean retriable) {
    super(message);
    this.retriable = retriable;
  }

  public boolean isRetriable() {
    return this.retriable;
  }
}
