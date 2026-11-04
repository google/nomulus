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

import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  effect,
  untracked,
  ViewEncapsulation,
  ChangeDetectionStrategy,
} from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource } from '@angular/material/table';
import { take } from 'rxjs';
import { MaterialModule } from 'src/app/material.module';
import { RegistrarService } from 'src/app/registrar/registrar.service';
import {
  GlobalLoader,
  GlobalLoaderService,
} from 'src/app/shared/services/globalLoader.service';
import { ContactService, ViewReadyContact } from './contact.service';
import { ContactDetailsComponent } from './contactDetails.component';

@Component({
  selector: 'app-contact',
  imports: [MaterialModule, ContactDetailsComponent],
  templateUrl: './contact.component.html',
  styleUrls: ['./contact.component.scss'],
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContactComponent implements GlobalLoader {
  public static PATH = 'contact';

  dataSource: MatTableDataSource<ViewReadyContact> =
    new MatTableDataSource<ViewReadyContact>([]);
  columns = [
    {
      columnDef: 'name',
      header: 'Name',
      cell: (contact: ViewReadyContact) => `${contact.name}`,
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
    private registrarService: RegistrarService,
    protected globalLoader: GlobalLoaderService,
    private _snackBar: MatSnackBar
  ) {
    effect(() => {
      const contacts = this.contactService.contacts();
      if (contacts) {
        this.dataSource.data = contacts;
      }
    });
    effect(() => {
      if (this.registrarService.registrarId()) {
        untracked(() => {
          this.contactService.isContactDetailsView.set(false);
          this.contactService.isContactNewView.set(false);
          this.globalLoader.startGlobalLoader(this);
          this.contactService
            .fetchContacts()
            .pipe(take(1))
            .subscribe({
              complete: () => this.globalLoader.stopGlobalLoader(this),
              error: (err: HttpErrorResponse) => {
                this._snackBar.open(err.error || err.message);
                this.globalLoader.stopGlobalLoader(this);
              },
            });
        });
      }
    });
  }

  loadingTimeout() {
    this._snackBar.open('Timeout loading contacts');
  }

  openDetails(contact: ViewReadyContact) {
    this.contactService.setEditableContact(contact);
    this.contactService.isContactDetailsView.set(true);
  }

  openNewContact() {
    this.contactService.setEditableContact();
    this.contactService.isContactNewView.set(true);
  }
}
