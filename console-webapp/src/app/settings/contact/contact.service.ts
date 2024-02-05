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

import { Injectable, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';

export interface Contact {
  name: string;
  phoneNumber: string;
  emailAddress: string;
  registrarId?: string;
  faxNumber?: string;
  types: Array<string>;
  visibleInWhoisAsAdmin?: boolean;
  visibleInWhoisAsTech?: boolean;
  visibleInDomainWhoisAsAbuse?: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class ContactService {
  contacts = signal<Contact[]>([]);

  constructor(
    private backend: BackendService,
    private registrarService: RegistrarService
  ) {}

  // TODO: Come up with a better handling for registrarId
  fetchContacts(): Observable<Contact[]> {
    return this.backend.getContacts(this.registrarService.registrarId()).pipe(
      tap((contacts = []) => {
        this.contacts.set(contacts);
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
