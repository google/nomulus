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

import { Component, Injector } from '@angular/core';
import { Registrar, RegistrarService } from './registrar.service';
import { BreakpointObserver } from '@angular/cdk/layout';
import {
  MAT_BOTTOM_SHEET_DATA,
  MatBottomSheet,
  MatBottomSheetRef,
} from '@angular/material/bottom-sheet';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatChipInputEvent } from '@angular/material/chips';

const MOBILE_LAYOUT_BREAKPOINT = '(max-width: 599px)';

@Component({
  selector: 'app-registrar-details',
  templateUrl: './registrarDetails.component.html',
  styleUrls: ['./registrarDetails.component.scss'],
})
export class RegistrarDetailsComponent {
  registrarInEdit!: Registrar;
  private elementRef:
    | MatBottomSheetRef<RegistrarDetailsComponent>
    | MatDialogRef<RegistrarDetailsComponent>;

  constructor(
    protected registrarService: RegistrarService,
    private injector: Injector
  ) {
    // We only inject one, either Dialog or Bottom Sheet data
    // so one of the injectors is expected to fail
    try {
      var params = this.injector.get(MAT_DIALOG_DATA);
      this.elementRef = this.injector.get(MatDialogRef);
    } catch (e) {
      var params = this.injector.get(MAT_BOTTOM_SHEET_DATA);
      this.elementRef = this.injector.get(MatBottomSheetRef);
    }
    this.registrarInEdit = JSON.parse(JSON.stringify(params.registrar));
  }

  onCancel(e: MouseEvent) {
    if (this.elementRef instanceof MatBottomSheetRef) {
      this.elementRef.dismiss();
    } else if (this.elementRef instanceof MatDialogRef) {
      this.elementRef.close();
    }
  }

  saveAndClose(e: MouseEvent) {
    // TODO: Implement save call to API
    this.onCancel(e);
  }

  addTLD(e: MatChipInputEvent) {
    this.removeTLD(e.value); // Prevent dups
    this.registrarInEdit.allowedTlds = this.registrarInEdit.allowedTlds?.concat(
      [e.value.toLowerCase()]
    );
  }

  removeTLD(tld: string) {
    this.registrarInEdit.allowedTlds = this.registrarInEdit.allowedTlds?.filter(
      (v) => v != tld
    );
  }
}
