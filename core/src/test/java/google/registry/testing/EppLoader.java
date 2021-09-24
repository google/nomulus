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

package google.registry.testing;

import static google.registry.flows.FlowUtils.unmarshalEpp;
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import google.registry.flows.EppException;
import google.registry.model.eppinput.EppInput;
import java.util.Map;

/** Test extension that loads an Epp object from a file. */
public class EppLoader {

  private String eppXml;

  public EppLoader(Object context, String eppXmlFilename) {
    this(context, eppXmlFilename, ImmutableMap.of());
  }

  public EppLoader(Object context, String eppXmlFilename, Map<String, String> substitutions) {
    this.eppXml = loadFile(context.getClass(), eppXmlFilename, substitutions);
  }

  public EppInput getEpp() throws EppException {
    /*
     * TODO(b/120837374): we shouldn't use EppException in non-Flow tests. Find a way to use {@link
     * google.registry.model.eppcommon.EppXmlTransformer#unmarshal(Class, byte[])} in those tests
     * instead of {@link google.registry.flows.FlowUtils#unmarshalEpp(Class, byte[])}
     */
    return unmarshalEpp(EppInput.class, eppXml.getBytes(UTF_8));
  }

  public String getEppXml() {
    return eppXml;
  }

  public void replaceAll(String regex, String substitution) {
    eppXml = eppXml.replaceAll(regex, substitution);
  }
}
