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
import { Observable, switchMap, tap } from 'rxjs';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import { BackendService } from 'src/app/shared/services/backend.service';

export type contactType =
  | 'ADMIN'
  | 'ABUSE'
  | 'BILLING'
  | 'LEGAL'
  | 'MARKETING'
  | 'TECH'
  | 'WHOIS';

type contactTypesToUserFriendlyTypes = { [type in contactType]: string };

export const contactTypeToTextMap: contactTypesToUserFriendlyTypes = {
  ADMIN: 'Primary contact',
  ABUSE: 'Abuse contact',
  BILLING: 'Billing contact',
  LEGAL: 'Legal contact',
  MARKETING: 'Marketing contact',
  TECH: 'Technical contact',
  WHOIS: 'RDAP-Inquiry contact',
};

type UserFriendlyType = (typeof contactTypeToTextMap)[contactType];

export interface Contact {
  name: string;
  phoneNumber?: string;
  emailAddress: string;
  registrarId?: string;
  faxNumber?: string;
  types: Array<contactType>;
  visibleInWhoisAsAdmin?: boolean;
  visibleInWhoisAsTech?: boolean;
  visibleInDomainWhoisAsAbuse?: boolean;
}

export interface ViewReadyContact extends Contact {
  userFriendlyTypes: Array<UserFriendlyType>;
}

export function contactTypeToViewReadyContact(c: Contact): ViewReadyContact {
  return {
    ...c,
    userFriendlyTypes: c.types?.map((cType) => contactTypeToTextMap[cType]),
  };
}

@Injectable({
  providedIn: 'root',
})
export class ContactService {
  contacts = signal<ViewReadyContact[]>([]);
  contactInEdit!: ViewReadyContact;
  isContactDetailsView: boolean = false;
  isContactNewView: boolean = false;

  constructor(
    private backend: BackendService,
    private registrarService: RegistrarService
  ) {}

  setEditableContact(contact?: ViewReadyContact) {
    this.contactInEdit = contact
      ? contact
      : contactTypeToViewReadyContact({
          emailAddress: '',
          name: '',
          types: ['TECH'],
          faxNumber: '',
          phoneNumber: '',
          registrarId: '',
        });
  }

  fetchContacts(): Observable<Contact[]> {
    return this.backend.getContacts(this.registrarService.registrarId()).pipe(
      tap((contacts) => {
        this.contacts.set(contacts.map(contactTypeToViewReadyContact));
      })
    );
  }

  updateContact(contact: ViewReadyContact) {
    return this.backend
      .updateContact(this.registrarService.registrarId(), contact)
      .pipe(switchMap((_) => this.fetchContacts()));
  }

  addContact(contact: ViewReadyContact) {
    return this.backend
      .createContact(this.registrarService.registrarId(), contact)
      .pipe(switchMap((_) => this.fetchContacts()));
  }

  deleteContact(contact: ViewReadyContact) {
    return this.backend
      .deleteContact(this.registrarService.registrarId(), contact)
      .pipe(switchMap((_) => this.fetchContacts()));
  }
}
