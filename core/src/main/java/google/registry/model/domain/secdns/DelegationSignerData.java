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

package google.registry.model.domain.secdns;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.secdns.DelegationSignerData.DelegationSignerDataId;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/**
 * Holds the data necessary to construct a single Delegation Signer (DS) record for a domain.
 *
 * @see <a href="http://tools.ietf.org/html/rfc5910">RFC 5910</a>
 * @see <a href="http://tools.ietf.org/html/rfc4034">RFC 4034</a>
 */
@Embed
@XmlType(name = "dsData")
@Entity
@Table(indexes = @Index(columnList = "domainRepoId"))
@IdClass(DelegationSignerDataId.class)
public class DelegationSignerData extends ImmutableObject implements DatastoreAndSqlEntity {

  private DelegationSignerData() {}

  @Ignore @XmlTransient @javax.persistence.Id String domainRepoId;

  /** The identifier for this particular key in the domain. */
  @javax.persistence.Id
  @Column(nullable = false)
  int keyTag;

  /**
   * The algorithm used by this key.
   *
   * @see <a href="http://tools.ietf.org/html/rfc4034#appendix-A.1">RFC 4034 Appendix A.1</a>
   */
  @Column(nullable = false)
  @XmlElement(name = "alg")
  int algorithm;

  /**
   * The algorithm used to generate the digest.
   *
   * @see <a href="http://tools.ietf.org/html/rfc4034#appendix-A.2">RFC 4034 Appendix A.2</a>
   */
  @Column(nullable = false)
  int digestType;

  /**
   * The hexBinary digest of the public key.
   *
   * @see <a href="http://tools.ietf.org/html/rfc4034#section-5.1.4">RFC 4034 Section 5.1.4</a>
   */
  @Column(nullable = false)
  @XmlJavaTypeAdapter(HexBinaryAdapter.class)
  byte[] digest;

  public int getKeyTag() {
    return keyTag;
  }

  public int getAlgorithm() {
    return algorithm;
  }

  public int getDigestType() {
    return digestType;
  }

  public byte[] getDigest() {
    return digest;
  }

  public String getDigestAsString() {
    return digest == null ? "" : DatatypeConverter.printHexBinary(digest);
  }

  public DelegationSignerData cloneWithDomainRepoId(String domainRepoId) {
    DelegationSignerData clone = clone(this);
    clone.domainRepoId = checkArgumentNotNull(domainRepoId);
    return clone;
  }

  public DelegationSignerData cloneWithoutDomainRepoId() {
    DelegationSignerData clone = clone(this);
    clone.domainRepoId = null;
    return clone;
  }

  public static DelegationSignerData create(
      int keyTag, int algorithm, int digestType, byte[] digest, String domainRepoId) {
    DelegationSignerData instance = new DelegationSignerData();
    instance.keyTag = keyTag;
    instance.algorithm = algorithm;
    instance.digestType = digestType;
    instance.digest = digest;
    instance.domainRepoId = domainRepoId;
    return instance;
  }

  public static DelegationSignerData create(
      int keyTag, int algorithm, int digestType, byte[] digest) {
    return create(keyTag, algorithm, digestType, digest, null);
  }

  public static DelegationSignerData create(
      int keyTag, int algorithm, int digestType, String digestAsHex) {
    return create(keyTag, algorithm, digestType, DatatypeConverter.parseHexBinary(digestAsHex));
  }

  /**
   * Returns the presentation format of this DS record.
   *
   * @see <a href="https://tools.ietf.org/html/rfc4034#section-5.3">RFC 4034 Section 5.3</a>
   */
  public String toRrData() {
    return String.format(
        "%d %d %d %s",
        this.keyTag, this.algorithm, this.digestType, DatatypeConverter.printHexBinary(digest));
  }

  static class DelegationSignerDataId extends ImmutableObject implements Serializable {
    String domainRepoId;
    int keyTag;

    private DelegationSignerDataId() {}

    private DelegationSignerDataId(String domainRepoId, int keyTag) {
      this.domainRepoId = domainRepoId;
      this.keyTag = keyTag;
    }

    public static DelegationSignerDataId create(String domainRepoId, int keyTag) {
      return new DelegationSignerDataId(checkArgumentNotNull(domainRepoId), keyTag);
    }
  }
}
