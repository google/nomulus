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

import { Component, ViewChild, computed, effect } from '@angular/core';
import { Contact, ContactService } from './contact.service';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  DialogBottomSheetContent,
  DialogBottomSheetWrapper,
} from 'src/app/shared/components/dialogBottomSheet.component';
import { MatTableDataSource } from '@angular/material/table';

enum Operations {
  DELETE,
  ADD,
  UPDATE,
}

type ContactDetailsParams = {
  close: Function;
  data: {
    contact: Contact;
    operation: Operations;
  };
};

const typeToTextMap: { [key: string]: any }  = {
  'ADMIN': 'Primary contact',
  'ABUSE': 'Abuse contact',
  'BILLING': 'Billing contact',
  'LEGAL': 'Legal contact',
  'MARKETING': 'Marketing contact',
  'TECH': 'Technical contact',
  'WHOIS': 'WHOIS-Inquiry contact',
}

@Component({
  selector: 'app-contact-details-dialog',
  templateUrl: 'contactDetails.component.html',
  styleUrls: ['./contact.component.scss'],
})
export class ContactDetailsDialogComponent implements DialogBottomSheetContent {
  contact?: Contact;
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
    this.contact = structuredClone(params.data.contact);
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
  dataSource: MatTableDataSource<Contact> = new MatTableDataSource<Contact>([]);
  columns = [
    {
      columnDef: 'name',
      header: 'Name',
      cell: (contact: Contact) => `
        <div class="contact__name-column">
          <div class="contact__name-column-title">${contact.name}</div>
          <div class="contact__name-column-roles">${contact.types.map((t)=> typeToTextMap[t]).join(" • ")}</div>
          </div>
      `,
    },
    {
      columnDef: 'emailAddress',
      header: 'Email',
      cell: (contact: Contact) => `${contact.emailAddress || ''}`
    },
    {
      columnDef: 'phoneNumber',
      header: 'Phone',
      cell: (contact: Contact) => `${contact.phoneNumber || ''}`
    },
    {
      columnDef: 'faxNumber',
      header: 'Fax',
      cell: (contact: Contact) => `${contact.faxNumber || ''}`
    },
  ];
  displayedColumns = this.columns.map((c) => c.columnDef);


  @ViewChild('contactDetailsWrapper')
  detailsComponentWrapper!: DialogBottomSheetWrapper;

  loading: boolean = false;
  constructor(
    public contactService: ContactService,
    private _snackBar: MatSnackBar
  ) {
    this.loading = true;
    this.contactService.fetchContacts().subscribe(() => {
      this.loading = false;
    });
    effect(() => {
      if(this.contactService.contacts().length) {
        this.dataSource = new MatTableDataSource<Contact>(
          this.contactService.contacts()
        );
      } 
    })
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

  // openCreateNew(e: MouseEvent) {
  //   const newContact: Contact = {
  //     name: '',
  //     phoneNumber: '',
  //     emailAddress: '',
  //     types: [contactTypes[0].value],
  //   };
  //   this.openDetails(e, newContact, Operations.ADD);
  // }

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
