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

package google.registry.persistence.transaction;

import java.lang.reflect.Proxy;

/**
 * A dummy implementation for {@link JpaTransactionManager} which throws exception when any of its
 * method is invoked.
 *
 * <p>This is used to initialize the {@link TransactionManagerFactory#jpaTm} when running unit
 * tests, because obviously we cannot connect to the actual Cloud SQL backend in a unit test.
 *
 * <p>If a unit test needs to access the Cloud SQL database, it must add JpaTransactionManagerRule
 * as a JUnit rule in the test class.
 */
public class DummyJpaTransactionManager {

  /** Constructs a dummy {@link JpaTransactionManager} instance. */
  public static JpaTransactionManager create() {
    return (JpaTransactionManager)
        Proxy.newProxyInstance(
            JpaTransactionManager.class.getClassLoader(),
            new Class[] {JpaTransactionManager.class},
            (proxy, method, args) -> {
              throw new UnsupportedOperationException(
                  "JpaTransactionManager was not initialized as the runtime is detected as"
                      + " Unittest. Add JpaTransactionManagerRule in the unit test for"
                      + " initialization.");
            });
  }
}
