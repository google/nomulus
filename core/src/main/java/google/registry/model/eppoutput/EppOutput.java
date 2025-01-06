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

package google.registry.model.eppoutput;

import com.google.common.annotations.VisibleForTesting;
import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElements;
import jakarta.xml.bind.annotation.XmlRootElement;

/** This class represents the root EPP XML element for output. */
@XmlRootElement(name = "epp")
public class EppOutput extends ImmutableObject {
  @XmlElements({
      @XmlElement(name = "response", type = EppResponse.class),
      @XmlElement(name = "greeting", type = Greeting.class) })
  ResponseOrGreeting responseOrGreeting;

  public static EppOutput create(ResponseOrGreeting responseOrGreeting) {
    EppOutput instance = new EppOutput();
    instance.responseOrGreeting = responseOrGreeting;
    return instance;
  }

  @VisibleForTesting
  public boolean isSuccess() {
    return ((EppResponse) responseOrGreeting).result.getCode().isSuccess();
  }

  public EppResponse getResponse() {
    return (EppResponse) responseOrGreeting;
  }

  public boolean isResponse() {
    return responseOrGreeting instanceof EppResponse;
  }

  /** Marker interface for types allowed inside of an {@link EppOutput}. */
  public interface ResponseOrGreeting {}
}
