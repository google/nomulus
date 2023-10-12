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

import { Component, Inject } from '@angular/core';
import {
  MatDialog,
  MAT_DIALOG_DATA,
  MatDialogRef,
} from '@angular/material/dialog';
import {
  MatBottomSheet,
  MAT_BOTTOM_SHEET_DATA,
  MatBottomSheetRef,
} from '@angular/material/bottom-sheet';
import { Contact, ContactService } from './contact.service';
import { BreakpointObserver } from '@angular/cdk/layout';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';

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

let isMobile: boolean;

const contactTypes: Array<GroupedContacts> = [
  { value: 'ADMIN', label: 'Primary contact', contacts: [] },
  { value: 'ABUSE', label: 'Abuse contact', contacts: [] },
  { value: 'BILLING', label: 'Billing contact', contacts: [] },
  { value: 'LEGAL', label: 'Legal contact', contacts: [] },
  { value: 'MARKETING', label: 'Marketing contact', contacts: [] },
  { value: 'TECH', label: 'Technical contact', contacts: [] },
  { value: 'WHOIS', label: 'WHOIS-Inquiry contact', contacts: [] },
];

class ContactDetailsEventsResponder {
  private ref?: MatDialogRef<any> | MatBottomSheetRef;
  constructor() {
    this.onClose = this.onClose.bind(this);
  }

  setRef(ref: MatDialogRef<any> | MatBottomSheetRef) {
    this.ref = ref;
  }

  onClose() {
    if (this.ref == undefined) {
      throw "Reference to ContactDetailsDialogComponent hasn't been set. ";
    }
    if (this.ref instanceof MatBottomSheetRef) {
      this.ref.dismiss();
    } else if (this.ref instanceof MatDialogRef) {
      this.ref.close();
    }
  }
}

@Component({
  selector: 'app-contact-details-dialog',
  templateUrl: 'contact-details.component.html',
  styleUrls: ['./contact.component.scss'],
})
export class ContactDetailsDialogComponent {
  contact: Contact;
  contactTypes = contactTypes;
  operation: Operations;
  contactIndex: number;
  onCloseCallback: Function;

  constructor(
    public contactService: ContactService,
    private _snackBar: MatSnackBar,
    @Inject(isMobile ? MAT_BOTTOM_SHEET_DATA : MAT_DIALOG_DATA)
    public data: {
      onClose: Function;
      contact: Contact;
      operation: Operations;
    }
  ) {
    this.onCloseCallback = data.onClose;
    this.contactIndex = contactService.contacts.findIndex(
      (c) => c === data.contact
    );
    this.contact = JSON.parse(JSON.stringify(data.contact));
    this.operation = data.operation;
  }

  onClose(e: MouseEvent) {
    e.preventDefault();
    this.onCloseCallback.call(this);
  }

  saveAndClose(e: SubmitEvent) {
    e.preventDefault();
    if (!(e.target as HTMLFormElement).checkValidity()) {
      return;
    }
    let operationObservable;
    if (this.operation === Operations.ADD) {
      operationObservable = this.contactService.addContact(this.contact);
    } else if (this.operation === Operations.UPDATE) {
      operationObservable = this.contactService.updateContact(
        this.contactIndex,
        this.contact
      );
    } else {
      throw 'Unknown operation type';
    }

    operationObservable.subscribe({
      complete: this.onCloseCallback.bind(this),
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

  loading: boolean = false;
  constructor(
    private dialog: MatDialog,
    private bottomSheet: MatBottomSheet,
    private breakpointObserver: BreakpointObserver,
    public contactService: ContactService,
    private _snackBar: MatSnackBar
  ) {
    // TODO: Refactor to registrarId service
    this.loading = true;
    this.contactService.fetchContacts().subscribe(() => {
      this.loading = false;
    });
  }

  public get groupedData() {
    return this.contactService.contacts?.reduce((acc, contact) => {
      contact.types.forEach((type) => {
        acc
          .find((group: GroupedContacts) => group.value === type)
          ?.contacts.push(contact);
      });
      return acc;
    }, JSON.parse(JSON.stringify(contactTypes)));
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
    // TODO: handle orientation change
    isMobile = this.breakpointObserver.isMatched('(max-width: 599px)');
    const responder = new ContactDetailsEventsResponder();
    const config = { data: { onClose: responder.onClose, contact, operation } };

    if (isMobile) {
      const bottomSheetRef = this.bottomSheet.open(
        ContactDetailsDialogComponent,
        config
      );
      responder.setRef(bottomSheetRef);
    } else {
      const dialogRef = this.dialog.open(ContactDetailsDialogComponent, config);
      responder.setRef(dialogRef);
    }
  }
}
