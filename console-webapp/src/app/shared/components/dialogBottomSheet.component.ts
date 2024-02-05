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

import { BreakpointObserver } from '@angular/cdk/layout';
import { ComponentType } from '@angular/cdk/portal';
import { Component } from '@angular/core';
import {
  MatBottomSheet,
  MatBottomSheetRef,
} from '@angular/material/bottom-sheet';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';

const MOBILE_LAYOUT_BREAKPOINT = '(max-width: 480px)';

export interface DialogBottomSheetContent {
  init(data: Object): void;
}

/**
 * Wraps up a child component in an Angular Material Dalog for desktop or a Bottom Sheet
 * component for mobile depending on a screen resolution, with Breaking Point being 480px.
 * Child component is required to implement @see DialogBottomSheetContent interface
 */
@Component({
  selector: 'app-dialog-bottom-sheet-wrapper',
  template: '',
})
export class DialogBottomSheetWrapper {
  private elementRef?: MatBottomSheetRef | MatDialogRef<any>;

  constructor(
    private dialog: MatDialog,
    private bottomSheet: MatBottomSheet,
    protected breakpointObserver: BreakpointObserver
  ) {}

  open<T extends DialogBottomSheetContent>(
    component: ComponentType<T>,
    data: any
  ) {
    const config = { data, close: () => this.close() };
    if (this.breakpointObserver.isMatched(MOBILE_LAYOUT_BREAKPOINT)) {
      this.elementRef = this.bottomSheet.open(component);
      this.elementRef.instance.init(config);
    } else {
      this.elementRef = this.dialog.open(component);
      this.elementRef.componentInstance.init(config);
    }
  }

  close() {
    if (this.elementRef instanceof MatBottomSheetRef) {
      this.elementRef.dismiss();
    } else if (this.elementRef instanceof MatDialogRef) {
      this.elementRef.close();
    }
  }
}
