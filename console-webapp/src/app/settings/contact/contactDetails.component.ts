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
import { Component } from '@angular/core';
import { MatCheckboxChange } from '@angular/material/checkbox';
import { MatSnackBar } from '@angular/material/snack-bar';
import { first } from 'rxjs';
import {
  ContactService,
  contactType,
  contactTypeToTextMap,
} from './contact.service';

@Component({
  selector: 'app-contact-details',
  templateUrl: './contactDetails.component.html',
  styleUrls: ['./contactDetails.component.scss'],
  standalone: false,
})
export class ContactDetailsComponent {
  protected contactTypeToTextMap = contactTypeToTextMap;
  isEditing: boolean = false;

  constructor(
    protected contactService: ContactService,
    private _snackBar: MatSnackBar
  ) {}

  deleteContact() {
    if (
      confirm(
        `Please confirm deletion of contact ${this.contactService.contactInEdit.name}`
      )
    ) {
      this.contactService
        .deleteContact(this.contactService.contactInEdit)
        .pipe(first())
        .subscribe({
          error: (err: HttpErrorResponse) => {
            this._snackBar.open(err.error);
          },
          complete: () => {
            this.goBack();
          },
        });
    }
  }

  goBack() {
    if (this.isEditing) {
      this.isEditing = false;
    } else {
      this.contactService.isContactNewView = false;
      this.contactService.isContactDetailsView = false;
    }
  }

  save(e: SubmitEvent) {
    e.preventDefault();
    const request = this.contactService.isContactNewView
      ? this.contactService.addContact(this.contactService.contactInEdit)
      : this.contactService.saveContacts(this.contactService.contacts());
    request.subscribe({
      complete: () => {
        this.goBack();
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error);
      },
    });
  }

  checkboxIsChecked(type: string) {
    return this.contactService.contactInEdit.types.includes(
      type as contactType
    );
  }

  checkboxIsDisabled(type: string) {
    if (!this.contactService.isContactNewView && type === 'ADMIN') {
      return true;
    }
    return (
      this.contactService.contactInEdit.types.length === 1 &&
      this.contactService.contactInEdit.types[0] === (type as contactType)
    );
  }

  checkboxOnChange(event: MatCheckboxChange, type: string) {
    if (event.checked) {
      this.contactService.contactInEdit.types.push(type as contactType);
    } else {
      this.contactService.contactInEdit.types =
        this.contactService.contactInEdit.types.filter(
          (t) => t != (type as contactType)
        );
    }
  }

  emailAddressIsDisabled() {
    if (this.contactService.isContactNewView) {
      return true;
    }
    return this.contactService.contactInEdit.types.includes('ADMIN');
  }
}
