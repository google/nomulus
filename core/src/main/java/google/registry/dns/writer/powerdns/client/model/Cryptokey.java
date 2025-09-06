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

package google.registry.dns.writer.powerdns.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Cryptokey {

  public static Cryptokey createCryptokey(
      KeyType keytype, int bits, Boolean active, Boolean published, String algorithm) {
    Cryptokey k = new Cryptokey();
    k.setKeytype(keytype);
    k.setBits(bits);
    k.setAlgorithm(algorithm);
    k.setActive(active);
    k.setPublished(published);
    return k;
  }

  @JsonProperty("type")
  private String type;

  @JsonProperty("id")
  private Integer id;

  @JsonProperty("keytype")
  private KeyType keytype;

  @JsonProperty("active")
  private Boolean active;

  @JsonProperty("published")
  private Boolean published;

  @JsonProperty("dnskey")
  private String dnskey;

  @JsonProperty("ds")
  private List<String> ds;

  @JsonProperty("cds")
  private List<String> cds;

  @JsonProperty("privatekey")
  private String privatekey;

  @JsonProperty("algorithm")
  private String algorithm;

  @JsonProperty("bits")
  private Integer bits;

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public KeyType getKeytype() {
    return keytype;
  }

  public void setKeytype(KeyType keytype) {
    this.keytype = keytype;
  }

  public Boolean getActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }

  public Boolean getPublished() {
    return published;
  }

  public void setPublished(Boolean published) {
    this.published = published;
  }

  public String getDnskey() {
    return dnskey;
  }

  public void setDnskey(String dnskey) {
    this.dnskey = dnskey;
  }

  public List<String> getDs() {
    return ds;
  }

  public void setDs(List<String> ds) {
    this.ds = ds;
  }

  public List<String> getCds() {
    return cds;
  }

  public void setCds(List<String> cds) {
    this.cds = cds;
  }

  public String getPrivatekey() {
    return privatekey;
  }

  public void setPrivatekey(String privatekey) {
    this.privatekey = privatekey;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public void setAlgorithm(String algorithm) {
    this.algorithm = algorithm;
  }

  public Integer getBits() {
    return bits;
  }

  public void setBits(Integer bits) {
    this.bits = bits;
  }

  @Override
  public String toString() {
    return String.format(
        "{id:%s,keytype:%s,active:%s,published:%s,algorithm:%s,bits:%s}",
        id, keytype, active, published, algorithm, bits);
  }

  public enum KeyType {
    ksk,
    zsk,
    csk
  }
}
