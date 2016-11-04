// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.xjc;

import com.google.common.base.Charsets;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;

/**
 * JAXB element wrapper for java object serialization.
 *
 * Instances of {@link JaxbFragment} wrap a non-serializable JAXB element instance, and provide
 * hooks into the java object serialization process that allow the elements to be safely
 * marshalled and unmarshalled using {@link ObjectOutputStream} and {@link ObjectInputStream},
 * respectively.
 */
public class JaxbFragment<T> implements Serializable {

  private static final long serialVersionUID = 5651243983008818813L;

  private T instance;

  /**
   * Stores a JAXB element in a {@link JaxbFragment}
   */
  public static <T> JaxbFragment<T> create(T object) throws XmlException {
    JaxbFragment<T> fragment = new JaxbFragment<>();
    fragment.instance = object;
    return fragment;
  }

  /**
   * Serializes a JAXB element into xml bytes.
   */
  private static <T> byte[] freezeInstance(T instance) {
    try {
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      XjcXmlTransformer.marshalLenient(instance, bout, Charsets.UTF_8);
      return bout.toByteArray();
    } catch (Exception e) {
      // Blow up for bad objects
      throw new RuntimeException(e);
    }
  }

  /**
   * Deserializes a JAXB element from xml bytes.
   */
  private static <T> T unfreezeInstance(byte[] instanceData, Class<? extends Object> instanceType) {
    try {
      ByteArrayInputStream bin = new ByteArrayInputStream(instanceData);
      @SuppressWarnings("unchecked")
      T instance = (T) XjcXmlTransformer.unmarshal(instanceType, bin);
      return instance;
    } catch (XmlException e) {
      // Blow up for bad xml
      throw new RuntimeException(e);
    }
  }

  /**
   * Retrieves the JAXB element that is wrapped by this fragment.
   */
  public T getInstance() {
    return instance;
  }

  public String toString() {
    try {
      return new String(freezeInstance(instance), "utf-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  private void writeObject(ObjectOutputStream out) {
    try {
      // write instanceType, then instanceData
      out.writeObject(instance.getClass());
      out.writeObject(freezeInstance(instance));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void readObject(ObjectInputStream in) {
    // read instanceType, then instanceData
    Class<?> instanceType;
    byte[] instanceData;
    try {
      instanceType = (Class<?>)in.readObject();
      instanceData = (byte[])in.readObject();
    } catch (ClassNotFoundException | IOException e) {
      throw new RuntimeException(e);
    }
    instance = unfreezeInstance(instanceData, instanceType);
  }
}
