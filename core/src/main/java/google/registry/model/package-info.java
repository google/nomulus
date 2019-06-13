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

@XmlSchema(
    namespace = "urn:ietf:params:xml:ns:epp-1.0",
    xmlns = @XmlNs(namespaceURI = "urn:ietf:params:xml:ns:epp-1.0", prefix = ""),
    elementFormDefault = XmlNsForm.QUALIFIED)
@XmlAccessorType(XmlAccessType.FIELD)
@XmlJavaTypeAdapter(UtcDateTimeAdapter.class)
package google.registry.model;

import google.registry.xml.UtcDateTimeAdapter;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlNs;
import javax.xml.bind.annotation.XmlNsForm;
import javax.xml.bind.annotation.XmlSchema;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/*
 * This package defines all entities which are managed via EPP XML and persisted to the Datastore
 * via Objectify.
 *
 * <p>All first class entities are represented as a resource class - {@link
 * google.registry.model.domain.DomainBase}, {@link google.registry.model.host.HostResource}, {@link
 * google.registry.model.contact.ContactResource}, and {@link
 * google.registry.model.registrar.Registrar}. Resource objects are written in a single shared
 * entity group per TLD. All commands that operate on those entities are grouped in a "Command"
 * class- {@link google.registry.model.domain.DomainCommand}, {@link
 * google.registry.model.host.HostCommand}, {@link google.registry.model.contact.ContactCommand}.
 * The Resource does double duty as both the persisted representation and as the XML-marshallable
 * object returned in respond to Info commands.
 *
 * <p>Command classes are never persisted, and the Objectify annotations on the Create and Update
 * classes are purely for the benefit of the derived Resource classes that inherit from them.
 * Whenever a command that mutates the model is executed, a HistoryEvent is stored with the affected
 * Resource as its Datastore parent. All history entries have an indexed modification time field so
 * that the history can be read in chronological order.
 */
