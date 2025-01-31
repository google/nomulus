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
  ElementRef,
  ViewChild,
  ViewEncapsulation,
} from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Registrar, RegistrarService } from './registrar.service';

interface LocalizedAddressStreet {
  line1: string;
  line2: string;
  line3: string;
}

@Component({
  selector: 'app-new-registrar',
  templateUrl: './newRegistrar.component.html',
  styleUrls: ['./newRegistrar.component.scss'],
  encapsulation: ViewEncapsulation.None,
  standalone: false,
})
export default class NewRegistrarComponent {
  protected newRegistrar: Registrar;
  protected localizedAddressStreet: LocalizedAddressStreet;
  protected billingAccountMap: String = '';

  @ViewChild('form') form!: ElementRef;
  constructor(
    private registrarService: RegistrarService,
    private _snackBar: MatSnackBar
  ) {
    this.newRegistrar = {
      registrarId: '',
      url: '',
      whoisServer: '',
      registrarName: '',
      icannReferralEmail: '',
      localizedAddress: {
        city: '',
        state: '',
        zip: '',
        countryCode: '',
      },
    };
    this.localizedAddressStreet = {
      line1: '',
      line2: '',
      line3: '',
    };
  }

  onBillingAccountMapChange(val: String) {
    const billingAccountMap: { [key: string]: string } = {};
    this.newRegistrar.billingAccountMap = val.split('\n').reduce((acc, val) => {
      const [currency, billingCode] = val.split('=');
      acc[currency] = billingCode;
      return acc;
    }, billingAccountMap);
  }

  save(e: SubmitEvent) {
    e.preventDefault();
    if (this.form.nativeElement.checkValidity()) {
      const { line1, line2, line3 } = this.localizedAddressStreet;
      this.newRegistrar.localizedAddress.street = [line1, line2, line3].filter(
        (v) => !!v
      );
      this.registrarService.createRegistrar(this.newRegistrar).subscribe({
        complete: () => {
          this.goBack();
        },
        error: (err: HttpErrorResponse) => {
          this._snackBar.open(err.error);
        },
      });
    } else {
      this.form.nativeElement.reportValidity();
    }
  }

  goBack() {
    this.registrarService.inNewRegistrarMode.set(false);
  }
}
