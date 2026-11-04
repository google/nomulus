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

import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  linkedSignal,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatCheckboxChange } from '@angular/material/checkbox';
import { MatSnackBar } from '@angular/material/snack-bar';
import { first } from 'rxjs';
import { MaterialModule } from 'src/app/material.module';
import {
  GlobalLoader,
  GlobalLoaderService,
} from 'src/app/shared/services/globalLoader.service';
import {
  ContactService,
  contactType,
  contactTypeToTextMap,
  contactTypeToViewReadyContact,
} from './contact.service';

@Component({
  selector: 'app-contact-details',
  imports: [CommonModule, FormsModule, MaterialModule],
  templateUrl: './contactDetails.component.html',
  styleUrls: ['./contactDetails.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContactDetailsComponent implements GlobalLoader {
  protected contactTypeToTextMap = contactTypeToTextMap;
  contactInEdit = linkedSignal(() =>
    structuredClone(this.contactService.contactInEdit())
  );
  isEditing = signal(false);
  isUpdating = signal(false);

  constructor(
    protected contactService: ContactService,
    private _snackBar: MatSnackBar,
    protected globalLoader: GlobalLoaderService
  ) {}

  loadingTimeout() {
    this._snackBar.open('Timeout updating contact');
  }

  deleteContact() {
    const contact = this.contactInEdit();
    if (!contact) return;
    if (confirm(`Please confirm deletion of contact ${contact.name}`)) {
      this.isUpdating.set(true);
      this.globalLoader.startGlobalLoader(this);
      this.contactService
        .deleteContact(contact)
        .pipe(first())
        .subscribe({
          error: (err: HttpErrorResponse) => {
            this._snackBar.open(err.error || err.message);
            this.isUpdating.set(false);
            this.globalLoader.stopGlobalLoader(this);
          },
          complete: () => {
            this.isUpdating.set(false);
            this.globalLoader.stopGlobalLoader(this);
            this.goBack();
          },
        });
    }
  }

  goBack() {
    if (this.isEditing()) {
      this.contactInEdit.set(
        structuredClone(this.contactService.contactInEdit())
      );
      this.isEditing.set(false);
    } else {
      this.contactService.isContactNewView.set(false);
      this.contactService.isContactDetailsView.set(false);
    }
  }

  save(e: SubmitEvent) {
    e.preventDefault();
    const contact = this.contactInEdit();
    if (!contact || (contact.types || []).length === 0) {
      this._snackBar.open('Required to select contact type');
      return;
    }
    this.isUpdating.set(true);
    this.globalLoader.startGlobalLoader(this);
    const request = this.contactService.isContactNewView()
      ? this.contactService.addContact(contact)
      : this.contactService.updateContact(contact);
    request.subscribe({
      complete: () => {
        this.isUpdating.set(false);
        this.globalLoader.stopGlobalLoader(this);
        this.contactService.setEditableContact(
          contactTypeToViewReadyContact(contact)
        );
        this.goBack();
      },
      error: (err: HttpErrorResponse) => {
        this._snackBar.open(err.error || err.message);
        this.isUpdating.set(false);
        this.globalLoader.stopGlobalLoader(this);
      },
    });
  }

  shouldDisplayCheckbox(type: string) {
    return type !== 'ADMIN' || this.checkboxIsChecked(type);
  }

  checkboxIsChecked(type: string) {
    return !!this.contactInEdit()?.types.includes(type as contactType);
  }

  checkboxIsDisabled(type: string) {
    if (type === 'ADMIN') {
      return true;
    }
    const types = this.contactInEdit()?.types || [];
    return types.length === 1 && types[0] === (type as contactType);
  }

  checkboxOnChange(event: MatCheckboxChange, type: string) {
    const contact = this.contactInEdit();
    if (!contact) return;
    if (event.checked) {
      contact.types.push(type as contactType);
    } else {
      contact.types = contact.types.filter((t) => t != (type as contactType));
    }
  }

  emailAddressIsDisabled() {
    return !!this.contactInEdit()?.types.includes('ADMIN');
  }
}
