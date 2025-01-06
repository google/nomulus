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

package google.registry.monitoring.blackbox.exception;

/**
 * Base exception class for all instances when the action performed fails before we can determine
 * the state of the result, meaning the status is recorded as ERROR.
 */
public class UndeterminedStateException extends Exception {

  public UndeterminedStateException(String msg) {
    super(msg);
  }

  public UndeterminedStateException(Throwable e) {
    super(e);
  }
}
