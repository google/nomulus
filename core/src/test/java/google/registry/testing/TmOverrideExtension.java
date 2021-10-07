// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import google.registry.persistence.transaction.TransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension for overriding the {@link TransactionManager} in tests.
 *
 * <p>You will typically want to run this at <code>@Order(Order.DEFAULT + 1)</code> alongside a
 * {@link google.registry.persistence.transaction.JpaTransactionManagerExtension} or {@link
 * DatastoreEntityExtension} with default {@link org.junit.jupiter.api.Order}. The transaction
 * manager extension needs to run first so that when this override is called it's not trying to use
 * the default dummy one.
 *
 * <p>This extension is incompatible with {@link DualDatabaseTest}. Use either that or this, but not
 * both.
 */
public final class TmOverrideExtension implements BeforeEachCallback, AfterEachCallback {

  private static enum TmOverride {
    OFY,
    JPA;
  }

  private final TmOverride tmOverride;

  private TmOverrideExtension(TmOverride tmOverride) {
    this.tmOverride = tmOverride;
  }

  /** Use the {@link google.registry.model.ofy.DatastoreTransactionManager} for all tests. */
  public static TmOverrideExtension withOfy() {
    return new TmOverrideExtension(TmOverride.OFY);
  }

  /**
   * Use the {@link google.registry.persistence.transaction.JpaTransactionManager} for all tests.
   */
  public static TmOverrideExtension withJpa() {
    return new TmOverrideExtension(TmOverride.JPA);
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    TransactionManagerFactory.setTmOverrideForTest(
        tmOverride == TmOverride.OFY ? ofyTm() : jpaTm());
  }

  @Override
  public void afterEach(ExtensionContext context) {
    TransactionManagerFactory.removeTmOverrideForTest();
  }
}
