// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import { Component, ViewChild, computed } from '@angular/core';
import { Contact, ContactService } from './contact.service';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  DialogBottomSheetContent,
  DialogBottomSheetWrapper,
} from 'src/app/shared/components/dialogBottomSheet.component';

enum Operations {
  DELETE,
  ADD,
  UPDATE,
}

interface GroupedContacts {
  value: string;
  label: string;
  contacts: Array<Contact>;
}

type ContactDetailsParams = {
  close: Function;
  data: {
    contact: Contact;
    operation: Operations;
  };
};

const contactTypes: Array<GroupedContacts> = [
  { value: 'ADMIN', label: 'Primary contact', contacts: [] },
  { value: 'ABUSE', label: 'Abuse contact', contacts: [] },
  { value: 'BILLING', label: 'Billing contact', contacts: [] },
  { value: 'LEGAL', label: 'Legal contact', contacts: [] },
  { value: 'MARKETING', label: 'Marketing contact', contacts: [] },
  { value: 'TECH', label: 'Technical contact', contacts: [] },
  { value: 'WHOIS', label: 'WHOIS-Inquiry contact', contacts: [] },
];

@Component({
  selector: 'app-contact-details-dialog',
  templateUrl: 'contactDetails.component.html',
  styleUrls: ['./contact.component.scss'],
})
export class ContactDetailsDialogComponent implements DialogBottomSheetContent {
  contact?: Contact;
  contactTypes = contactTypes;
  contactIndex?: number;

  params?: ContactDetailsParams;

  constructor(
    public contactService: ContactService,
    private _snackBar: MatSnackBar
  ) {}

  init(params: ContactDetailsParams) {
    this.params = params;
    this.contactIndex = this.contactService
      .contacts()
      .findIndex((c) => c === params.data.contact);
    this.contact = JSON.parse(JSON.stringify(params.data.contact));
  }

  close() {
    this.params?.close();
  }

  saveAndClose(e: SubmitEvent) {
    e.preventDefault();
    if (!this.contact || this.contactIndex === undefined) return;
    if (!(e.target as HTMLFormElement).checkValidity()) {
      return;
    }
    const operation = this.params?.data.operation;
    let operationObservable;
    if (operation === Operations.ADD) {
      operationObservable = this.contactService.addContact(this.contact);
    } else if (operation === Operations.UPDATE) {
      operationObservable = this.contactService.updateContact(
        this.contactIndex,
        this.contact
      );
    } else {
      throw 'Unknown operation type';
    }

    operationObservable.subscribe({
      complete: () => this.close(),
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error);
      },
    });
  }
}

@Component({
  selector: 'app-contact',
  templateUrl: './contact.component.html',
  styleUrls: ['./contact.component.scss'],
})
export default class ContactComponent {
  public static PATH = 'contact';
  public groupedContacts = computed(() => {
    return this.contactService.contacts().reduce((acc, contact) => {
      contact.types.forEach((contactType) => {
        acc
          .find((group: GroupedContacts) => group.value === contactType)
          ?.contacts.push(contact);
      });
      return acc;
    }, JSON.parse(JSON.stringify(contactTypes)));
  });

  @ViewChild('contactDetailsWrapper')
  detailsComponentWrapper!: DialogBottomSheetWrapper;

  loading: boolean = false;
  constructor(
    public contactService: ContactService,
    private _snackBar: MatSnackBar
  ) {
    // TODO: Refactor to registrarId service
    this.loading = true;
    this.contactService.fetchContacts().subscribe(() => {
      this.loading = false;
    });
  }

  deleteContact(contact: Contact) {
    if (confirm(`Please confirm contact ${contact.name} delete`)) {
      this.contactService.deleteContact(contact).subscribe({
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error);
        },
      });
    }
  }

  openCreateNew(e: MouseEvent) {
    const newContact: Contact = {
      name: '',
      phoneNumber: '',
      emailAddress: '',
      types: [contactTypes[0].value],
    };
    this.openDetails(e, newContact, Operations.ADD);
  }

  openDetails(
    e: MouseEvent,
    contact: Contact,
    operation: Operations = Operations.UPDATE
  ) {
    e.preventDefault();
    this.detailsComponentWrapper.open<ContactDetailsDialogComponent>(
      ContactDetailsDialogComponent,
      { contact, operation }
    );
  }
}
