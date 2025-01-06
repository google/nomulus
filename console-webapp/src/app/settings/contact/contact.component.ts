// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import { Component, effect, ViewEncapsulation } from '@angular/core';
import { MatTableDataSource } from '@angular/material/table';
import { take } from 'rxjs';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import {
  ContactService,
  contactTypeToViewReadyContact,
  ViewReadyContact,
} from './contact.service';

@Component({
  selector: 'app-contact',
  templateUrl: './contact.component.html',
  styleUrls: ['./contact.component.scss'],
  encapsulation: ViewEncapsulation.None,
})
export default class ContactComponent {
  public static PATH = 'contact';

  dataSource: MatTableDataSource<ViewReadyContact> =
    new MatTableDataSource<ViewReadyContact>([]);
  columns = [
    {
      columnDef: 'name',
      header: 'Name',
      cell: (contact: ViewReadyContact) => `
        <div class="contact__name-column">
          <div class="contact__name-column-title">${contact.name}</div>
          <div class="contact__name-column-roles">${contact.userFriendlyTypes.join(
            ' • '
          )}</div>
          </div>
      `,
    },
    {
      columnDef: 'emailAddress',
      header: 'Email',
      cell: (contact: ViewReadyContact) => `${contact.emailAddress || ''}`,
    },
    {
      columnDef: 'phoneNumber',
      header: 'Phone',
      cell: (contact: ViewReadyContact) => `${contact.phoneNumber || ''}`,
    },
    {
      columnDef: 'faxNumber',
      header: 'Fax',
      cell: (contact: ViewReadyContact) => `${contact.faxNumber || ''}`,
    },
  ];
  displayedColumns = this.columns.map((c) => c.columnDef);

  constructor(
    public contactService: ContactService,
    private registrarService: RegistrarService
  ) {
    effect(() => {
      if (this.contactService.contacts()) {
        this.dataSource = new MatTableDataSource<ViewReadyContact>(
          this.contactService.contacts()
        );
      }
    });
    effect(() => {
      if (this.registrarService.registrarId()) {
        this.contactService.isContactDetailsView = false;
        this.contactService.isContactNewView = false;
        this.contactService.fetchContacts().pipe(take(1)).subscribe();
      }
    });
  }

  openDetails(contact: ViewReadyContact) {
    this.contactService.setEditableContact(contact);
    this.contactService.isContactDetailsView = true;
  }

  openNewContact() {
    this.contactService.setEditableContact();
    this.contactService.isContactNewView = true;
  }
}
