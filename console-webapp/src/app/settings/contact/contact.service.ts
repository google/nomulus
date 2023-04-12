import { HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, catchError, delay, of, tap } from 'rxjs';
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
  contacts: Contact[] = [];

  constructor(private backend: BackendService) {}

  // TODO: Come up with a better handling for registrarId
  fetchContacts(registrarId: String): Observable<Contact[]> {
    return this.backend.getContacts<Contact[]>(registrarId).pipe(
      tap((contacts) => {
        this.contacts = contacts;
      })
    );
  }

  saveContacts(
    contacts: Contact[],
    registrarId?: String
  ): Observable<Contact[]> {
    return this.backend
      .postContacts<Contact[]>(registrarId || 'default', contacts)
      .pipe(
        tap((_) => {
          this.contacts = contacts;
        })
      );
  }

  updateContact(index: number, contact: Contact) {
    const newContacts = this.contacts.map((c, i) =>
      i === index ? contact : c
    );
    return this.saveContacts(newContacts);
  }

  addContact(contact: Contact) {
    const newContacts = this.contacts.concat([contact]);
    return this.saveContacts(newContacts);
  }

  deleteContact(contact: Contact) {
    const newContacts = this.contacts.filter((c) => c !== contact);
    return this.saveContacts(newContacts);
  }
}
