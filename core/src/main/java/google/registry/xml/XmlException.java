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

package google.registry.xml;

/**
 * An exception thrown by {@link XmlTransformer} when marshalling or unmarshalling fails.
 *
 * <p>Upstream errors such as {@link jakarta.xml.bind.JAXBException} will be wrapped by this class.
 */
public class XmlException extends Exception {

  public XmlException(String message) {
    super(message);
  }

  public XmlException(Throwable cause) {
    super(cause);
  }
}
