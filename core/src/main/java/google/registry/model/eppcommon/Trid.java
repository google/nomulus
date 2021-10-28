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

package google.registry.model.eppcommon;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.googlecode.objectify.annotation.Embed;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

/**
 * "A {@code TRID} (transaction identifier) element containing the transaction identifier assigned
 * by the server to the command for which the response is being returned. The transaction identifier
 * is formed using the {@code clTRID} associated with the command if supplied by the client and a
 * {@code svTRID} (server transaction identifier) that is assigned by and unique to the server."
 */
@Embed
@XmlType(propOrder = {"clientTransactionId", "serverTransactionId"})
@javax.persistence.Embeddable
public class Trid extends ImmutableObject implements UnsafeSerializable {

  /** The server transaction id. */
  @XmlElement(name = "svTRID", namespace = "urn:ietf:params:xml:ns:epp-1.0")
  String serverTransactionId;

  /** The client transaction id, if provided by the client, otherwise null. */
  @XmlElement(name = "clTRID", namespace = "urn:ietf:params:xml:ns:epp-1.0")
  @Nullable
  String clientTransactionId;

  public String getServerTransactionId() {
    return serverTransactionId;
  }

  public Optional<String> getClientTransactionId() {
    return Optional.ofNullable(clientTransactionId);
  }

  public static Trid create(@Nullable String clientTransactionId, String serverTransactionId) {
    checkArgumentNotNull(serverTransactionId, "serverTransactionId cannot be null");
    Trid instance = new Trid();
    instance.clientTransactionId = clientTransactionId;
    instance.serverTransactionId = serverTransactionId;
    return instance;
  }
}
