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

package google.registry.model.domain.secdns;

import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.ImmutableObject;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.MappedSuperclass;
import javax.persistence.Transient;
import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.HexBinaryAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/** Base class for {@link DelegationSignerData} and {@link DelegationSignerDataHistory}. */
@Embed
@MappedSuperclass
@Access(AccessType.FIELD)
public abstract class DelegationSignerDataBase extends ImmutableObject
    implements DatastoreAndSqlEntity {

  @Ignore @XmlTransient String domainRepoId;

  /** The identifier for this particular key in the domain. */
  @Transient int keyTag;

  /**
   * The algorithm used by this key.
   *
   * @see <a href="http://tools.ietf.org/html/rfc4034#appendix-A.1">RFC 4034 Appendix A.1</a>
   */
  @Transient
  @XmlElement(name = "alg")
  int algorithm;

  /**
   * The algorithm used to generate the digest.
   *
   * @see <a href="http://tools.ietf.org/html/rfc4034#appendix-A.2">RFC 4034 Appendix A.2</a>
   */
  @Transient int digestType;

  /**
   * The hexBinary digest of the public key.
   *
   * @see <a href="http://tools.ietf.org/html/rfc4034#section-5.1.4">RFC 4034 Section 5.1.4</a>
   */
  @Transient
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

  /**
   * Sets the key tag.
   *
   * <p>This method is private because it is only used by Hibernate.
   */
  @SuppressWarnings("unused")
  private void setKeyTag(int keyTag) {
    this.keyTag = keyTag;
  }

  /**
   * Sets the algorithm.
   *
   * <p>This method is private because it is only used by Hibernate.
   */
  @SuppressWarnings("unused")
  private void setAlgorithm(int algorithm) {
    this.algorithm = algorithm;
  }

  /**
   * Sets the digest type.
   *
   * <p>This method is private because it is only used by Hibernate.
   */
  @SuppressWarnings("unused")
  private void setDigestType(int digestType) {
    this.digestType = digestType;
  }

  /**
   * Sets the digest.
   *
   * <p>This method is private because it is only used by Hibernate.
   */
  @SuppressWarnings("unused")
  private void setDigest(byte[] digest) {
    this.digest = digest;
  }

  public String getDigestAsString() {
    return digest == null ? "" : DatatypeConverter.printHexBinary(digest);
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
}
