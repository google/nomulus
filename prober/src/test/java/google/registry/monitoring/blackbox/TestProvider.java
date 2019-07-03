// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox;

import javax.inject.Provider;

/** Implementation of Provider that serves as simple Provider in {@link Protocol} handlerProviders */
public class TestProvider<E> implements Provider<E> {

  private E obj;

  public TestProvider(E obj) {
    this.obj = obj;
  }

  @Override
  public E get() {
    return obj;
  }
}

