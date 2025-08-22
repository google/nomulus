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
public class Zone {
  @JsonProperty("id")
  private String id;

  @JsonProperty("name")
  private String name;

  @JsonProperty("type")
  private String type;

  @JsonProperty("url")
  private String url;

  @JsonProperty("kind")
  private ZoneKind kind;

  @JsonProperty("rrsets")
  private List<RRSet> rrsets;

  @JsonProperty("serial")
  private Integer serial;

  @JsonProperty("notified_serial")
  private Integer notifiedSerial;

  @JsonProperty("edited_serial")
  private Integer editedSerial;

  @JsonProperty("masters")
  private List<String> masters;

  @JsonProperty("dnssec")
  private Boolean dnssec;

  @JsonProperty("nsec3param")
  private String nsec3param;

  @JsonProperty("nsec3narrow")
  private Boolean nsec3narrow;

  @JsonProperty("presigned")
  private Boolean presigned;

  @JsonProperty("soa_edit")
  private String soaEdit;

  @JsonProperty("soa_edit_api")
  private String soaEditApi;

  @JsonProperty("api_rectify")
  private Boolean apiRectify;

  @JsonProperty("zone")
  private String zone;

  @JsonProperty("catalog")
  private String catalog;

  @JsonProperty("account")
  private String account;

  @JsonProperty("nameservers")
  private List<String> nameservers;

  @JsonProperty("master_tsig_key_ids")
  private List<String> masterTsigKeyIds;

  @JsonProperty("slave_tsig_key_ids")
  private List<String> slaveTsigKeyIds;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public ZoneKind getKind() {
    return kind;
  }

  public void setKind(ZoneKind kind) {
    this.kind = kind;
  }

  public List<RRSet> getRrsets() {
    return rrsets;
  }

  public void setRrsets(List<RRSet> rrsets) {
    this.rrsets = rrsets;
  }

  public Integer getSerial() {
    return serial;
  }

  public void setSerial(Integer serial) {
    this.serial = serial;
  }

  public Integer getNotifiedSerial() {
    return notifiedSerial;
  }

  public void setNotifiedSerial(Integer notifiedSerial) {
    this.notifiedSerial = notifiedSerial;
  }

  public Integer getEditedSerial() {
    return editedSerial;
  }

  public void setEditedSerial(Integer editedSerial) {
    this.editedSerial = editedSerial;
  }

  public List<String> getMasters() {
    return masters;
  }

  public void setMasters(List<String> masters) {
    this.masters = masters;
  }

  public Boolean getDnssec() {
    return dnssec;
  }

  public void setDnssec(Boolean dnssec) {
    this.dnssec = dnssec;
  }

  public String getNsec3param() {
    return nsec3param;
  }

  public void setNsec3param(String nsec3param) {
    this.nsec3param = nsec3param;
  }

  public Boolean getNsec3narrow() {
    return nsec3narrow;
  }

  public void setNsec3narrow(Boolean nsec3narrow) {
    this.nsec3narrow = nsec3narrow;
  }

  public Boolean getPresigned() {
    return presigned;
  }

  public void setPresigned(Boolean presigned) {
    this.presigned = presigned;
  }

  public String getSoaEdit() {
    return soaEdit;
  }

  public void setSoaEdit(String soaEdit) {
    this.soaEdit = soaEdit;
  }

  public String getSoaEditApi() {
    return soaEditApi;
  }

  public void setSoaEditApi(String soaEditApi) {
    this.soaEditApi = soaEditApi;
  }

  public Boolean getApiRectify() {
    return apiRectify;
  }

  public void setApiRectify(Boolean apiRectify) {
    this.apiRectify = apiRectify;
  }

  public String getZone() {
    return zone;
  }

  public void setZone(String zone) {
    this.zone = zone;
  }

  public String getCatalog() {
    return catalog;
  }

  public void setCatalog(String catalog) {
    this.catalog = catalog;
  }

  public String getAccount() {
    return account;
  }

  public void setAccount(String account) {
    this.account = account;
  }

  public List<String> getNameservers() {
    return nameservers;
  }

  public void setNameservers(List<String> nameservers) {
    this.nameservers = nameservers;
  }

  public List<String> getMasterTsigKeyIds() {
    return masterTsigKeyIds;
  }

  public void setMasterTsigKeyIds(List<String> masterTsigKeyIds) {
    this.masterTsigKeyIds = masterTsigKeyIds;
  }

  public List<String> getSlaveTsigKeyIds() {
    return slaveTsigKeyIds;
  }

  public void setSlaveTsigKeyIds(List<String> slaveTsigKeyIds) {
    this.slaveTsigKeyIds = slaveTsigKeyIds;
  }

  @Override
  public String toString() {
    long deletedCount =
        rrsets.stream().filter(rrset -> rrset.getChangeType() == RRSet.ChangeType.DELETE).count();
    long updatedCount =
        rrsets.stream().filter(rrset -> rrset.getChangeType() == RRSet.ChangeType.REPLACE).count();
    return String.format(
        "{id:%s,name:%s,deleted:%d,updated:%d,total:%d}",
        id, name, deletedCount, updatedCount, rrsets.size());
  }

  public enum ZoneKind {
    Native,
    Master,
    Slave,
    Producer,
    Consumer
  }
}
