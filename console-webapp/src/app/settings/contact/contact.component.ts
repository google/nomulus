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
import { BreakpointObserver } from '@angular/cdk/layout';

let isMobile: boolean = false;

interface Contact {
  name: string;
  phoneNumber: string;
  emailAddress: string;
}

interface GroupedContacts {
  ADMIN: Array<Contact>;
  BILLING: Array<Contact>;
  TECH: Array<Contact>;
  OTHER: Array<Contact>;
}

interface iContactDetailsEventsResponder {
  onSave: Function;
  onClose: Function;
  setRef: Function;
}

class ContactDetailsEventsResponder implements iContactDetailsEventsResponder {
  private ref?: MatDialogRef<any> | MatBottomSheetRef;
  constructor() {
    this.onClose = this.onClose.bind(this);
    this.onSave = this.onSave.bind(this);
  }

  setRef(ref: MatDialogRef<any> | MatBottomSheetRef) {
    this.ref = ref;
  }
  onClose() {
    if (this.ref === undefined) {
      throw "Reference to ContactDetailsDialogComponent hasn't been set. ";
    }
    if (this.ref instanceof MatBottomSheetRef) {
      this.ref.dismiss();
    } else if (this.ref instanceof MatDialogRef) {
      this.ref.close();
    }
  }
  onSave() {
    // TODO: Submit a save request here
    this.onClose();
  }
}

@Component({
  selector: 'app-contact-details-dialog',
  templateUrl: 'contact-details.component.html',
  styleUrls: ['./contact.component.less'],
})
export class ContactDetailsDialogComponent {
  onSave!: Function;
  onClose!: Function;

  constructor(
    @Inject(isMobile ? MAT_BOTTOM_SHEET_DATA : MAT_DIALOG_DATA) public data: any
  ) {
    this.onSave = data.onSave;
    this.onClose = data.onClose;
  }
}

@Component({
  selector: 'app-contact',
  templateUrl: './contact.component.html',
  styleUrls: ['./contact.component.less'],
})
export default class ContactComponent {
  constructor(
    private dialog: MatDialog,
    private bottomSheet: MatBottomSheet,
    private breakpointObserver: BreakpointObserver
  ) {}
  mockData = [
    {
      name: 'Michael Scott',
      emailAddress: 'michaelscott@google.com',
      registryLockEmailAddress: null,
      phoneNumber: null,
      faxNumber: null,
      types: '',
      visibleInWhoisAsAdmin: false,
      visibleInWhoisAsTech: false,
      visibleInDomainWhoisAsAbuse: false,
      allowedToSetRegistryLockPassword: false,
      registryLockAllowed: false,
      loginEmailAddress: 'michaelscott@google.com',
    },
    {
      name: 'Dwight Schrute',
      emailAddress: 'dwightschrute@google.com',
      registryLockEmailAddress: null,
      phoneNumber: null,
      faxNumber: null,
      types: 'ADMIN',
      visibleInWhoisAsAdmin: false,
      visibleInWhoisAsTech: false,
      visibleInDomainWhoisAsAbuse: false,
      allowedToSetRegistryLockPassword: false,
      registryLockAllowed: false,
      loginEmailAddress: 'dwightschrute@google.com',
    },
    {
      name: 'Pam',
      emailAddress: 'dundermifflin1@google.com',
      registryLockEmailAddress: null,
      phoneNumber: null,
      faxNumber: null,
      types: 'BILLING',
      visibleInWhoisAsAdmin: false,
      visibleInWhoisAsTech: false,
      visibleInDomainWhoisAsAbuse: false,
      allowedToSetRegistryLockPassword: false,
      registryLockAllowed: false,
      loginEmailAddress: 'dundermifflin1@google.com',
    },
    {
      name: 'Jim',
      emailAddress: 'dundermifflin2@google.com',
      registryLockEmailAddress: null,
      phoneNumber: null,
      faxNumber: null,
      types: 'BILLING',
      visibleInWhoisAsAdmin: false,
      visibleInWhoisAsTech: false,
      visibleInDomainWhoisAsAbuse: false,
      allowedToSetRegistryLockPassword: false,
      registryLockAllowed: false,
      loginEmailAddress: 'dundermifflin2@google.com',
    },
  ];

  public get groupedData() {
    const data: GroupedContacts = {
      ADMIN: [],
      BILLING: [],
      OTHER: [],
      TECH: [],
    };
    return this.mockData.reduce(
      (acc, { name, phoneNumber, emailAddress, types }) => {
        const type = types || 'OTHER';
        acc[type as keyof typeof data].push({
          name,
          phoneNumber: phoneNumber || '',
          emailAddress,
        });
        return acc;
      },
      data
    );
  }

  openDetails(e: Event) {
    e.preventDefault();
    isMobile = this.breakpointObserver.isMatched('(max-width: 599px)');
    const responder = new ContactDetailsEventsResponder();
    const config = { data: { onClose: responder.onClose, onSave: responder.onSave } };
    
    if (isMobile) {
      const bottomSheetRef = this.bottomSheet.open(ContactDetailsDialogComponent, config);
      responder.setRef(bottomSheetRef);
    } else {
      const dialogRef = this.dialog.open(ContactDetailsDialogComponent, config);
      responder.setRef(dialogRef);
    }
  }
}
