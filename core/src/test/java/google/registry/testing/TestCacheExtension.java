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

package google.registry.testing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import google.registry.model.EppResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.registry.label.PremiumListDatastoreDao;
import google.registry.schema.tld.PremiumListSqlDao;
import java.util.Map;
import java.util.Optional;
import org.joda.time.Duration;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A JUnit extension that overloads cache expiry for tests.
 *
 * <p>This rule is necessary because many caches in the system are singleton and referenced through
 * static fields.
 */
public class TestCacheExtension implements BeforeEachCallback, AfterEachCallback {

  private final ImmutableList<TestCacheHandler> cacheHandlers;

  private TestCacheExtension(ImmutableList<TestCacheHandler> cacheHandlers) {
    this.cacheHandlers = cacheHandlers;
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    cacheHandlers.forEach(TestCacheHandler::before);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    cacheHandlers.forEach(TestCacheHandler::after);
  }

  /** Builder for {@link TestCacheExtension}. */
  public static class Builder {
    private final Map<String, TestCacheHandler> cacheHandlerMap = Maps.newHashMap();

    public Builder withEppResourceCache(Duration expiry) {
      cacheHandlerMap.put(
          "EppResource.cacheEppResources",
          new TestCacheHandler(EppResource::setCacheForTest, expiry));
      return this;
    }

    public Builder withForeignIndexKeyCache(Duration expiry) {
      cacheHandlerMap.put(
          "ForeignKeyIndex.cacheForeignKeyIndexes",
          new TestCacheHandler(ForeignKeyIndex::setCacheForTest, expiry));
      return this;
    }

    public Builder withPremiumListsCache(Duration expiry) {
      cacheHandlerMap.put(
          "PremiumListSqlDao.premiumListCache",
          new TestCacheHandler(PremiumListSqlDao::setPremiumListCacheForTest, expiry));
      cacheHandlerMap.put(
          "PremiumListDatastoreDao.premiumListCache",
          new TestCacheHandler(PremiumListDatastoreDao::setPremiumListCacheForTest, expiry));
      return this;
    }

    public Builder withPremiumListEntriesCache(Duration expiry) {
      cacheHandlerMap.put(
          "PremiumList.cachePremiumListEntries",
          new TestCacheHandler(PremiumListDatastoreDao::setPremiumListEntriesCacheForTest, expiry));
      return this;
    }

    public TestCacheExtension build() {
      return new TestCacheExtension(ImmutableList.copyOf(cacheHandlerMap.values()));
    }
  }

  static class TestCacheHandler {
    private final TestCacheSetter setter;
    private final Duration testExpiry;

    private TestCacheHandler(TestCacheSetter setter, Duration testExpiry) {
      this.setter = setter;
      this.testExpiry = testExpiry;
    }

    void before() {
      setter.setCache(Optional.of(testExpiry));
    }

    void after() {
      setter.setCache(Optional.empty());
    }
  }

  @FunctionalInterface
  interface TestCacheSetter {

    /**
     * Creates a new cache for use during tests.
     *
     * @param expiry expiry for the test cache. If not present, use default setting
     */
    void setCache(Optional<Duration> expiry);
  }
}
