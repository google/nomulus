// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

import { Injectable, effect, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';

export interface Contact {
  name: string;
  phoneNumber?: string;
  emailAddress: string;
  registrarId?: string;
  faxNumber?: string;
  types: Array<string>;
  visibleInWhoisAsAdmin?: boolean;
  visibleInWhoisAsTech?: boolean;
  visibleInDomainWhoisAsAbuse?: boolean;
}

const mockContacts = [{"name":"test","emailAddress":"test@google.com","registrarId":"contacts-alpha","phoneNumber":"+1.2125650000","types":["ADMIN", "BILLING"],"visibleInWhoisAsAdmin":true,"visibleInWhoisAsTech":true,"visibleInDomainWhoisAsAbuse":true},{"name":"Test test","emailAddress":"testtest@google.com","registrarId":"contacts-alpha","types":["ADMIN"],"visibleInWhoisAsAdmin":false,"visibleInWhoisAsTech":false,"visibleInDomainWhoisAsAbuse":false},{"name":"nobody","emailAddress":"nobody@google.com","registrarId":"contacts-alpha","types":["ADMIN"],"visibleInWhoisAsAdmin":false,"visibleInWhoisAsTech":false,"visibleInDomainWhoisAsAbuse":false}];

@Injectable({
  providedIn: 'root',
})
export class ContactService {
  contacts = signal<Contact[]>([]);

  constructor(
    private backend: BackendService,
    private registrarService: RegistrarService
  ) {}

  fetchContacts(): Observable<Contact[]> {
    return this.backend.getContacts(this.registrarService.registrarId()).pipe(
      tap((contacts = []) => {
        if(contacts.length) {
          this.contacts.set(contacts);
        } else {
          // TODO: REMOVE
          this.contacts.set(mockContacts);
        }
      })
    );
  }

  saveContacts(contacts: Contact[]): Observable<Contact[]> {
    return this.backend
      .postContacts(this.registrarService.registrarId(), contacts)
      .pipe(
        tap((_) => {
          this.contacts.set(contacts);
        })
      );
  }

  updateContact(index: number, contact: Contact) {
    const newContacts = this.contacts().map((c, i) =>
      i === index ? contact : c
    );
    return this.saveContacts(newContacts);
  }

  addContact(contact: Contact) {
    const newContacts = this.contacts().concat([contact]);
    return this.saveContacts(newContacts);
  }

  deleteContact(contact: Contact) {
    const newContacts = this.contacts().filter((c) => c !== contact);
    return this.saveContacts(newContacts);
  }
}
