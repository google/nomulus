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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.googlecode.objectify.Key;
import google.registry.persistence.VKey;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * Translator factory for VKey.
 *
 * <p>These get translated to a string containing the URL safe encoding of the objectify key
 * followed by a (url-unsafe) ampersand delimiter and the SQL key.
 */
public class VKeyTranslatorFactory<T> extends AbstractSimpleTranslatorFactory<VKey, String> {
  private final Class<T> refClass;

  public VKeyTranslatorFactory(Class<T> refClass) {
    super(VKey.class);
    this.refClass = refClass;
  }

  @Override
  public SimpleTranslator<VKey, String> createTranslator() {
    return new SimpleTranslator<VKey, String>() {
      @Override
      public VKey loadValue(String datastoreValue) {
        int pos = datastoreValue.indexOf('&');
        Key ofyKey = null;
        String sqlKey = null;
        if (pos > 0) {
          // We have an objectify key.
          ofyKey = Key.create(datastoreValue.substring(0, pos));
        }

        if (pos < datastoreValue.length() - 1) {
          // We have an SQL key.
          sqlKey = decode(datastoreValue.substring(pos + 1));
        }

        return VKey.create(refClass, sqlKey, ofyKey);
      }

      @Override
      public String saveValue(VKey key) {
        return ((key.getOfyKey() == null) ? "" : key.getOfyKey().getString())
            + "&"
            + ((key.getSqlKey() == null) ? "" : encode(key.getSqlKey().toString()));
      }
    };
  }

  private static String encode(String val) {
    try {
      return URLEncoder.encode(val, UTF_8.toString());
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  private static String decode(String encoded) {
    try {
      return URLDecoder.decode(encoded, UTF_8.toString());
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }
}
