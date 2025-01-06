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

package google.registry.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import google.registry.model.adapters.ClassProcessingTypeAdapterFactory;
import google.registry.model.adapters.CurrencyJsonAdapter;
import google.registry.model.adapters.SerializableJsonTypeAdapter;
import google.registry.util.CidrAddressBlock;
import google.registry.util.CidrAddressBlock.CidrAddressBlockAdapter;
import google.registry.util.DateTimeTypeAdapter;
import java.io.IOException;
import java.io.Serializable;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/** Utility class for methods related to GSON and necessary GSON processing. */
public class GsonUtils {

  /** Interface to enable GSON post-processing on a particular object after deserialization. */
  public interface GsonPostProcessable {
    void postProcess();
  }

  /**
   * Some objects may require post-processing after deserialization from JSON.
   *
   * <p>We do this upon deserialization in order to make sure that the object matches the format
   * that we expect to be stored in the database. See {@link
   * google.registry.model.eppcommon.Address} for an example.
   */
  public static class GsonPostProcessableTypeAdapterFactory implements TypeAdapterFactory {
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
      TypeAdapter<T> originalAdapter = gson.getDelegateAdapter(this, type);
      if (!GsonPostProcessable.class.isAssignableFrom(type.getRawType())) {
        return originalAdapter;
      }
      return new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, T value) throws IOException {
          originalAdapter.write(out, value);
        }

        @Override
        public T read(JsonReader in) throws IOException {
          T t = originalAdapter.read(in);
          ((GsonPostProcessable) t).postProcess();
          return t;
        }
      };
    }
  }

  public static Gson provideGson() {
    return new GsonBuilder()
        .registerTypeAdapter(CidrAddressBlock.class, new CidrAddressBlockAdapter())
        .registerTypeAdapter(CurrencyUnit.class, new CurrencyJsonAdapter())
        .registerTypeAdapter(DateTime.class, new DateTimeTypeAdapter())
        .registerTypeAdapter(Serializable.class, new SerializableJsonTypeAdapter())
        .registerTypeAdapterFactory(new ClassProcessingTypeAdapterFactory())
        .registerTypeAdapterFactory(new GsonPostProcessableTypeAdapterFactory())
        .excludeFieldsWithoutExposeAnnotation()
        .create();
  }

  private GsonUtils() {}
}
