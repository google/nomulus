// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkState;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/** Keeps track of the current class and method under test. */
public class TestCaseWatcher extends TestWatcher {

  private Description currentDescription;

  @Override
  protected void starting(Description description) {
    this.currentDescription = description;
  }

  private void validateState() {
    checkState(
        currentDescription != null,
        "Tests have not started yet. Make sure invocation is from inner rule or test code.");
  }

  public String getTestClass() {
    validateState();
    return currentDescription.getClassName();
  }

  public String getTestMethod() {
    validateState();
    return currentDescription.getMethodName();
  }
}
