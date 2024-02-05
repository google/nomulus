import { Directive, HostListener } from '@angular/core';
import { Location } from '@angular/common';

@Directive({
  selector: '[backButton]',
})
export class LocationBackDirective {
  constructor(private location: Location) {}

  @HostListener('click')
  onClick() {
    this.location.back();
  }
}
