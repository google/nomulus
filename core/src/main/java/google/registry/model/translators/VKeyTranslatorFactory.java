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

package google.registry.model.translators;

import static com.google.common.base.Functions.identity;
import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.EntityClasses.ALL_CLASSES;

import com.google.appengine.api.datastore.Key;
import com.google.common.annotations.VisibleForTesting;
import com.googlecode.objectify.annotation.EntitySubclass;
import google.registry.persistence.VKey;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Translator factory for VKey.
 *
 * <p>These get translated to a string containing the URL safe encoding of the objectify key
 * followed by a (url-unsafe) ampersand delimiter and the SQL key.
 */
public class VKeyTranslatorFactory extends AbstractSimpleTranslatorFactory<VKey, Key> {

  // Class registry allowing us to restore the original class object from the unqualified class
  // name, which is all the datastore key gives us.
  // Note that entities annotated with @EntitySubclass are removed because they share the same
  // kind of the key with their parent class.
  private static final Map<String, Class<?>> CLASS_REGISTRY =
      ALL_CLASSES.stream()
          .filter(clazz -> !clazz.isAnnotationPresent(EntitySubclass.class))
          .collect(Collectors.toMap(com.googlecode.objectify.Key::getKind, identity()));

  public VKeyTranslatorFactory() {
    super(VKey.class);
  }

  /** Create a VKey from a raw datastore key. */
  @Nullable
  public static VKey<?> createVKey(@Nullable Key datastoreKey) {
    if (datastoreKey == null) {
      return null;
    }
    return createVKey(com.googlecode.objectify.Key.create(datastoreKey));
  }

  /** Create a VKey from an objectify Key. */
  @Nullable
  @SuppressWarnings("unchecked")
  public static <T> VKey<T> createVKey(@Nullable com.googlecode.objectify.Key<T> key) {
    if (key == null) {
      return null;
    }

    // Try to create the VKey from its reference type.
    Class<T> clazz = (Class<T>) CLASS_REGISTRY.get(key.getKind());
    checkArgument(clazz != null, "Unknown Key type: %s", key.getKind());
    try {
      Method createVKeyMethod =
          clazz.getDeclaredMethod("createVKey", com.googlecode.objectify.Key.class);
      return (VKey<T>) createVKeyMethod.invoke(null, new Object[] {key});
    } catch (NoSuchMethodException e) {
      checkArgument(
          key.getParent() == null,
          "Cannot auto-convert key %s of kind %s because it has a parent.  Add a createVKey(Key) "
              + "method for it.",
          key,
          key.getKind());
      if (key.getName() != null) {
        return VKey.create(clazz, key.getName(), key);
      } else {
        return VKey.create(clazz, key.getId(), key);
      }
    } catch (IllegalAccessException | InvocationTargetException e) {
      // If we have a createVKey(Key) method with incorrect permissions or that is non-static, this
      // is probably an error so let's reported.
      throw new RuntimeException(e);
    }
  }

  @VisibleForTesting
  public static void addTestEntityClass(Class<?> clazz) {
    CLASS_REGISTRY.put(com.googlecode.objectify.Key.getKind(clazz), clazz);
  }

  @Override
  public SimpleTranslator<VKey, Key> createTranslator() {
    return new SimpleTranslator<VKey, Key>() {

      @Nullable
      @Override
      public VKey loadValue(@Nullable Key datastoreValue) {
        return createVKey(datastoreValue);
      }

      @Nullable
      @Override
      public Key saveValue(@Nullable VKey key) {
        return key == null ? null : key.getOfyKey().getRaw();
      }
    };
  }
}
