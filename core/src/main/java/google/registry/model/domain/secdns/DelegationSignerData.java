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
import google.registry.model.ImmutableObject;
import google.registry.model.domain.secdns.DelegationSignerData.DelegationSignerDataId;
import java.io.Serializable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.xml.bind.DatatypeConverter;
import javax.xml.bind.annotation.XmlType;

/**
 * Holds the data necessary to construct a single Delegation Signer (DS) record for a domain.
 *
 * @see <a href="http://tools.ietf.org/html/rfc5910">RFC 5910</a>
 * @see <a href="http://tools.ietf.org/html/rfc4034">RFC 4034</a>
 */
@Embed
@XmlType(name = "dsData")
@Entity
@IdClass(DelegationSignerDataId.class)
@Table(indexes = @Index(columnList = "domainRepoId"))
public class DelegationSignerData extends DelegationSignerDataBase {

  private DelegationSignerData() {}

  @Override
  @Id
  @Access(AccessType.PROPERTY)
  public int getKeyTag() {
    return super.getKeyTag();
  }

  @Override
  @Id
  @Access(AccessType.PROPERTY)
  public int getAlgorithm() {
    return super.getAlgorithm();
  }

  @Override
  @Id
  @Access(AccessType.PROPERTY)
  public int getDigestType() {
    return super.getDigestType();
  }

  @Override
  @Id
  @Access(AccessType.PROPERTY)
  public byte[] getDigest() {
    return super.getDigest();
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

  /** Class to represent the composite primary key of {@link DelegationSignerData} entity. */
  static class DelegationSignerDataId extends ImmutableObject implements Serializable {
    int keyTag;

    int algorithm;

    int digestType;

    byte[] digest;

    /** Hibernate requires this default constructor. */
    private DelegationSignerDataId() {}

    /** Constructs a {link DelegationSignerDataId} instance. */
    DelegationSignerDataId(int keyTag, int algorithm, int digestType, byte[] digest) {
      this.keyTag = keyTag;
      this.algorithm = algorithm;
      this.digestType = digestType;
      this.digest = digest;
    }

    /**
     * Returns the key tag.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private int getKeyTag() {
      return keyTag;
    }

    /**
     * Returns the algorithm.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private int getAlgorithm() {
      return algorithm;
    }

    /**
     * Returns the digest type.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private int getDigestType() {
      return digestType;
    }

    /**
     * Returns the digest.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private byte[] getDigest() {
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

    public static DelegationSignerDataId create(
        int keyTag, int algorithm, int digestType, byte[] digest) {
      return new DelegationSignerDataId(
          keyTag, algorithm, digestType, checkArgumentNotNull(digest));
    }
  }
}
