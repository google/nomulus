// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.ofy;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.googlecode.objectify.ObjectifyService.factory;
import static google.registry.util.TypeUtils.hasAnnotation;

import com.google.appengine.api.datastore.AsyncDatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceConfig;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Objectify;
import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.impl.translate.TranslatorFactory;
import com.googlecode.objectify.impl.translate.opt.joda.MoneyStringTranslatorFactory;
import google.registry.config.RegistryEnvironment;
import google.registry.model.Buildable;
import google.registry.model.EntityClasses;
import google.registry.model.ImmutableObject;
import google.registry.model.translators.BloomFilterOfStringTranslatorFactory;
import google.registry.model.translators.CidrAddressBlockTranslatorFactory;
import google.registry.model.translators.CommitLogRevisionsTranslatorFactory;
import google.registry.model.translators.CreateAutoTimestampTranslatorFactory;
import google.registry.model.translators.CurrencyUnitTranslatorFactory;
import google.registry.model.translators.DurationTranslatorFactory;
import google.registry.model.translators.EppHistoryVKeyTranslatorFactory;
import google.registry.model.translators.InetAddressTranslatorFactory;
import google.registry.model.translators.ReadableInstantUtcTranslatorFactory;
import google.registry.model.translators.UpdateAutoTimestampTranslatorFactory;
import google.registry.model.translators.VKeyTranslatorFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An instance of Ofy, obtained via {@code #ofy()}, should be used to access all persistable
 * objects. The class contains a static initializer to call factory().register(...) on all
 * persistable objects in this package.
 */
public class ObjectifyService {

  /**
   * A placeholder String passed into DatastoreService.allocateIds that ensures that all ids are
   * initialized from the same id pool.
   */
  public static final String APP_WIDE_ALLOCATION_KIND = "common";

  /** A singleton instance of our Ofy wrapper. */
  private static final Ofy OFY = new Ofy(null);

  /**
   * Returns a singleton {@link Ofy} instance.
   *
   * <p><b>Deprecated:</b> This will go away once everything injects {@code Ofy}.
   */
  public static Ofy ofy() {
    return OFY;
  }

  static {
    initOfyOnce();
  }

  /** Ensures that Objectify has been fully initialized. */
  public static void initOfy() {
    // This method doesn't actually do anything; it's here so that callers have something to call
    // to ensure that the static initialization of ObjectifyService has been performed (which Java
    // guarantees will happen exactly once, before any static methods are invoked).
    //
    // See JLS section 12.4: http://docs.oracle.com/javase/specs/jls/se7/html/jls-12.html#jls-12.4
  }

  /**
   * Performs static initialization for Objectify to register types and do other setup.
   *
   * <p>This method is non-idempotent, so it should only be called exactly once, which is achieved
   * by calling it from this class's static initializer block.
   */
  private static void initOfyOnce() {
    // Set an ObjectifyFactory that uses our extended ObjectifyImpl.
    // The "false" argument means that we are not using the v5-style Objectify embedded entities.
    com.googlecode.objectify.ObjectifyService.setFactory(new ObjectifyFactory(false) {
      @Override
      public Objectify begin() {
        return new SessionKeyExposingObjectify(this);
      }

      @Override
      protected AsyncDatastoreService createRawAsyncDatastoreService(DatastoreServiceConfig cfg) {
        // In the unit test environment, wrap the Datastore service in a proxy that can be used to
        // examine the number of requests sent to Datastore.
        AsyncDatastoreService service = super.createRawAsyncDatastoreService(cfg);
        return RegistryEnvironment.get().equals(RegistryEnvironment.UNITTEST)
            ? new RequestCapturingAsyncDatastoreService(service)
            : service;
      }});

    // Translators must be registered before any entities can be registered.
    registerTranslators();
    registerEntityClasses(EntityClasses.ALL_CLASSES);
  }

  /** Register translators that allow less common types to be stored directly in Datastore. */
  private static void registerTranslators() {
    for (TranslatorFactory<?> translatorFactory :
        ImmutableList.of(
            new BloomFilterOfStringTranslatorFactory(),
            new CidrAddressBlockTranslatorFactory(),
            new CommitLogRevisionsTranslatorFactory(),
            new CreateAutoTimestampTranslatorFactory(),
            new CurrencyUnitTranslatorFactory(),
            new DurationTranslatorFactory(),
            new EppHistoryVKeyTranslatorFactory(),
            new InetAddressTranslatorFactory(),
            new MoneyStringTranslatorFactory(),
            new ReadableInstantUtcTranslatorFactory(),
            new VKeyTranslatorFactory(),
            new UpdateAutoTimestampTranslatorFactory())) {
      factory().getTranslators().add(translatorFactory);
    }
  }

  /** Register classes that can be persisted via Objectify as Datastore entities. */
  private static void registerEntityClasses(
      ImmutableSet<Class<? extends ImmutableObject>> entityClasses) {
    // Register all the @Entity classes before any @EntitySubclass classes so that we can check
    // that every @Entity registration is a new kind and every @EntitySubclass registration is not.
    // This is future-proofing for Objectify 5.x where the registration logic gets less lenient.

    for (Class<?> clazz :
        Streams.concat(
                entityClasses.stream().filter(hasAnnotation(Entity.class)),
                entityClasses.stream().filter(hasAnnotation(Entity.class).negate()))
            .collect(toImmutableSet())) {
      String kind = Key.getKind(clazz);
      boolean registered = factory().getMetadata(kind) != null;
      if (clazz.isAnnotationPresent(Entity.class)) {
        // Objectify silently replaces current registration for a given kind string when a different
        // class is registered again for this kind. For simplicity's sake, throw an exception on any
        // re-registration.
        checkState(
            !registered,
            "Kind '%s' already registered, cannot register new @Entity %s",
            kind,
            clazz.getCanonicalName());
      } else if (clazz.isAnnotationPresent(EntitySubclass.class)) {
        // Ensure that any @EntitySubclass classes have also had their parent @Entity registered,
        // which Objectify nominally requires but doesn't enforce in 4.x (though it may in 5.x).
        checkState(registered,
            "No base entity for kind '%s' registered yet, cannot register new @EntitySubclass %s",
            kind, clazz.getCanonicalName());
      }
      com.googlecode.objectify.ObjectifyService.register(clazz);
      // Autogenerated ids make the commit log code very difficult since we won't always be able
      // to create a key for an entity immediately when requesting a save. So, we require such
      // entities to implement google.registry.model.Buildable as its build() function allocates the
      // id to the entity.
      if (factory().getMetadata(clazz).getKeyMetadata().isIdGeneratable()) {
        checkState(
            Buildable.class.isAssignableFrom(clazz),
            "Can't register %s: Entity with autogenerated ids (@Id on a Long) must implement"
                + " google.registry.model.Buildable.",
            kind);
      }
    }
  }

  /** Counts of used ids for use in unit tests. Outside tests this is never used. */
  private static final AtomicLong nextTestId = new AtomicLong(1); // ids cannot be zero

  /** Allocates an id. */
  public static long allocateId() {
    return RegistryEnvironment.UNITTEST.equals(RegistryEnvironment.get())
      ? nextTestId.getAndIncrement()
      : DatastoreServiceFactory.getDatastoreService()
          .allocateIds(APP_WIDE_ALLOCATION_KIND, 1)
          .iterator()
          .next()
          .getId();
  }

  /** Resets the global test id counter (i.e. sets the next id to 1). */
  @VisibleForTesting
  public static void resetNextTestId() {
    checkState(RegistryEnvironment.UNITTEST.equals(RegistryEnvironment.get()),
        "Can't call resetTestIdCounts() from RegistryEnvironment.%s",
        RegistryEnvironment.get());
    nextTestId.set(1); // ids cannot be zero
  }
}
