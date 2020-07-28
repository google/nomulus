// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A JUnit extension that captures the {@link ExtensionContext} when {@link
 * BeforeEachCallback#beforeEach(ExtensionContext)} is called.
 *
 * <p>This is useful when writing tests of JUnit extensions themselves (very meta), i.e., when you
 * want to be able to control the lifecycle of another extension yourself by manually calling the
 * before/after callbacks, and need the {@link ExtensionContext} instance to pass to those
 * callbacks.
 */
public class ContextCapturingMetaExtension implements BeforeEachCallback {

  private ExtensionContext context;

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    this.context = context;
  }

  public ExtensionContext getContext() {
    return this.context;
  }
}
