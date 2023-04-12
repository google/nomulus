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

import { Component } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';

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

@Component({
  selector: 'app-contact-details-dialog',
  templateUrl: 'contact-details.component.html',
  styleUrls: ['./contact.component.less'],
})
export class ContactDetailsDialogComponent {
  save() {
    // TODO: Add save call to the sever here
  }
}

@Component({
  selector: 'app-contact',
  templateUrl: './contact.component.html',
  styleUrls: ['./contact.component.less'],
})
export default class ContactComponent {
  constructor(public dialog: MatDialog) {}
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

  openDialog(e: Event) {
    e.preventDefault();
    const dialogRef = this.dialog.open(ContactDetailsDialogComponent);

    dialogRef.afterClosed().subscribe((result) => {
      console.log(`Dialog result: ${result}`);
    });
  }
}
