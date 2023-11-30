import { BreakpointObserver } from '@angular/cdk/layout';
import { ComponentType } from '@angular/cdk/portal';
import { Component } from '@angular/core';
import { MatBottomSheet } from '@angular/material/bottom-sheet';
import { MatDialog } from '@angular/material/dialog';

const MOBILE_LAYOUT_BREAKPOINT = '(max-width: 599px)';

@Component({
  selector: 'app-dialog-bottom-sheet-wrapper',
  template: '',
})
export class DialogBottomSheetWrapper {
  constructor(
    private dialog: MatDialog,
    private bottomSheet: MatBottomSheet,
    protected breakpointObserver: BreakpointObserver
  ) {}

  open<T>(component: ComponentType<T>, data: any) {
    const config = { data };
    if (this.breakpointObserver.isMatched(MOBILE_LAYOUT_BREAKPOINT)) {
      return this.bottomSheet.open(component, config);
    } else {
      return this.dialog.open(component, config);
    }
  }
}
