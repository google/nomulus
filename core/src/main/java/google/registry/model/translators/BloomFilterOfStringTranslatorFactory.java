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

package google.registry.model.translators;

import static com.google.common.hash.Funnels.unencodedCharsFunnel;
import static com.googlecode.objectify.repackaged.gentyref.GenericTypeReflector.erase;
import static com.googlecode.objectify.repackaged.gentyref.GenericTypeReflector.getTypeParameter;

import com.google.appengine.api.datastore.Blob;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.google.common.reflect.TypeToken;
import com.googlecode.objectify.impl.Path;
import com.googlecode.objectify.impl.Property;
import com.googlecode.objectify.impl.translate.CreateContext;
import com.googlecode.objectify.impl.translate.LoadContext;
import com.googlecode.objectify.impl.translate.SaveContext;
import com.googlecode.objectify.impl.translate.Translator;
import com.googlecode.objectify.impl.translate.TranslatorFactory;
import com.googlecode.objectify.impl.translate.ValueTranslator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import javax.annotation.Nullable;

/** Stores CharSequence {@link BloomFilter}s as blobs. */
public class BloomFilterOfStringTranslatorFactory
    implements TranslatorFactory<BloomFilter<String>> {

  @Override
  public Translator<BloomFilter<String>> create(
      Path path, Property property, Type type, CreateContext ctx) {
    if (!BloomFilter.class.equals(erase(type))) {
      return null;  // Skip me and try to find another matching translator
    }
    Type fieldBloomFilterType = getTypeParameter(type, BloomFilter.class.getTypeParameters()[0]);
    if (fieldBloomFilterType == null) {
      return null;  // No type information is available
    }
    if (!TypeToken.of(String.class).getType().equals(fieldBloomFilterType)) {
      return null;  // We can only handle BloomFilters of CharSequences
    }

    return new ValueTranslator<BloomFilter<String>, Blob>(path, Blob.class) {

      @Override
      @Nullable
      protected BloomFilter<String> loadValue(Blob value, LoadContext ctx) {
        if (value == null) {
          return null;
        }
        try {
          @SuppressWarnings("unchecked")
          Funnel<String> castedFunnel = (Funnel<String>) (Funnel<?>) unencodedCharsFunnel();
          return BloomFilter.readFrom(new ByteArrayInputStream(value.getBytes()), castedFunnel);
        } catch (IOException e) {
          throw new IllegalStateException("Error loading Bloom filter data", e);
        }
      }

      @Override
      @Nullable
      protected Blob saveValue(BloomFilter<String> value, SaveContext ctx) {
        if (value == null) {
          return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
          value.writeTo(bos);
        } catch (IOException e) {
          throw new IllegalStateException("Error saving Bloom filter data", e);
        }
        return new Blob(bos.toByteArray());
      }
    };
  }
}
