import { BreakpointObserver } from '@angular/cdk/layout';
import { ComponentType } from '@angular/cdk/portal';
import { Component } from '@angular/core';
import {
  MatBottomSheet,
  MatBottomSheetRef,
} from '@angular/material/bottom-sheet';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';

const MOBILE_LAYOUT_BREAKPOINT = '(max-width: 599px)';

export interface DialogBottomSheetContent {
  init(data: Object): void;
}

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

  open<T>(component: ComponentType<T>, data: any) {
    const config = { data, onClose: () => this.onClose() };
    if (this.breakpointObserver.isMatched(MOBILE_LAYOUT_BREAKPOINT)) {
      this.elementRef = this.bottomSheet.open(component);
      this.elementRef.instance.init(config);
    } else {
      this.elementRef = this.dialog.open(component);
      this.elementRef.componentInstance.init(config);
    }
  }

  onClose() {
    if (this.elementRef instanceof MatBottomSheetRef) {
      this.elementRef.dismiss();
    } else if (this.elementRef instanceof MatDialogRef) {
      this.elementRef.close();
    }
  }
}
