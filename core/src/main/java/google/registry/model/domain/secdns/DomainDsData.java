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

package google.registry.model.domain.secdns;

import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.ImmutableObject;
import google.registry.model.domain.secdns.DomainDsData.DomainDsDataId;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.xml.bind.DatatypeConverter;
import jakarta.xml.bind.annotation.XmlType;
import java.io.Serializable;

/**
 * Holds the data necessary to construct a single Delegation Signer (DS) record for a domain.
 *
 * @see <a href="http://tools.ietf.org/html/rfc5910">RFC 5910</a>
 * @see <a href="http://tools.ietf.org/html/rfc4034">RFC 4034</a>
 */
@XmlType(name = "dsData")
@Entity(name = "DelegationSignerData")
@IdClass(DomainDsDataId.class)
@Table(name = "DelegationSignerData", indexes = @Index(columnList = "domainRepoId"))
public class DomainDsData extends DomainDsDataBase {

  private DomainDsData() {}

  @Override
  @Id
  @Access(AccessType.PROPERTY)
  public String getDomainRepoId() {
    return super.getDomainRepoId();
  }

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

  public DomainDsData cloneWithDomainRepoId(String domainRepoId) {
    DomainDsData clone = clone(this);
    clone.domainRepoId = checkArgumentNotNull(domainRepoId);
    return clone;
  }

  public DomainDsData cloneWithoutDomainRepoId() {
    DomainDsData clone = clone(this);
    clone.domainRepoId = null;
    return clone;
  }

  public static DomainDsData create(
      int keyTag, int algorithm, int digestType, byte[] digest, String domainRepoId) {
    DomainDsData instance = new DomainDsData();
    instance.keyTag = keyTag;
    instance.algorithm = algorithm;
    instance.digestType = digestType;
    instance.digest = digest;
    instance.domainRepoId = domainRepoId;
    return instance;
  }

  public static DomainDsData create(int keyTag, int algorithm, int digestType, byte[] digest) {
    return create(keyTag, algorithm, digestType, digest, null);
  }

  public static DomainDsData create(int keyTag, int algorithm, int digestType, String digestAsHex) {
    return create(keyTag, algorithm, digestType, DatatypeConverter.parseHexBinary(digestAsHex));
  }

  public static DomainDsData create(DomainDsDataHistory history) {
    return create(
        history.keyTag,
        history.algorithm,
        history.digestType,
        history.digest,
        history.domainRepoId);
  }

  /** Class to represent the composite primary key of {@link DomainDsData} entity. */
  static class DomainDsDataId extends ImmutableObject implements Serializable {

    String domainRepoId;

    int keyTag;

    int algorithm;

    int digestType;

    byte[] digest;

    /** Hibernate requires this default constructor. */
    private DomainDsDataId() {}

    /** Constructs a {link DomainDsDataId} instance. */
    DomainDsDataId(String domainRepoId, int keyTag, int algorithm, int digestType, byte[] digest) {
      this.domainRepoId = domainRepoId;
      this.keyTag = keyTag;
      this.algorithm = algorithm;
      this.digestType = digestType;
      this.digest = digest;
    }

    /**
     * Returns the domain repository ID.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private String getDomainRepoId() {
      return domainRepoId;
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
     * Sets the domain repository ID.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private void setDomainRepoId(String domainRepoId) {
      this.domainRepoId = domainRepoId;
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

    public static DomainDsDataId create(
        String domainRepoId, int keyTag, int algorithm, int digestType, byte[] digest) {
      return new DomainDsDataId(
          domainRepoId, keyTag, algorithm, digestType, checkArgumentNotNull(digest));
    }
  }
}
